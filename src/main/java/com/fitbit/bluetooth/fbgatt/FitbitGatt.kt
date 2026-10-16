/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.MainThread
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.fitbit.bluetooth.fbgatt.advertise.AdvertiseError
import com.fitbit.bluetooth.fbgatt.advertise.BitGattAdvertiser
import com.fitbit.bluetooth.fbgatt.logging.BitgattDebugTree
import com.fitbit.bluetooth.fbgatt.logging.BitgattReleaseTree
import com.fitbit.bluetooth.fbgatt.strategies.BluetoothOffClearGattServerStrategy
import com.fitbit.bluetooth.fbgatt.tx.AddGattServerServiceTransaction
import com.fitbit.bluetooth.fbgatt.tx.ClearServerServicesTransaction
import com.fitbit.bluetooth.fbgatt.tx.GattConnectTransaction
import com.fitbit.bluetooth.fbgatt.util.GattUtils
import com.fitbit.bluetooth.fbgatt.util.LooperWatchdog
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import timber.log.Timber

/**
 * Process-wide GATT entry point. [getInstance] stays. Advertising is explicit via [getAdvertiser]
 * and is not started by [startServer] or [bluetoothOn].
 */
open class FitbitGatt private constructor() : PeripheralScanner.TrackerScannerListener, BluetoothRadioStatusListener.BluetoothOnListener {

    interface FitbitGattCallback {
        fun onBluetoothPeripheralDiscovered(connection: GattConnection)
        fun onBluetoothPeripheralDisconnected(connection: GattConnection)
        fun onFitbitGattReady()
        fun onFitbitGattStartFailed()
        fun onScanStarted()
        fun onScanStopped()
        fun onPendingIntentScanStopped()
        fun onPendingIntentScanStarted()

        @MainThread
        fun onBluetoothOff()

        @MainThread
        fun onBluetoothOn()

        @MainThread
        fun onBluetoothTurningOn()

        @MainThread
        fun onBluetoothTurningOff()

        fun onScanFailed(failure: ScanFailure) {}

        fun onAdvertiseStateChanged(advertising: Boolean, @Nullable error: AdvertiseError?) {}
    }

    private val connectionMap = ConcurrentHashMap<FitbitBluetoothDevice, GattConnection>()
    private var overallGattEventListeners = CopyOnWriteArrayList<FitbitGattCallback>()
    private var gattServer: BluetoothGattServer? = null
    private val servicesToAdd = CopyOnWriteArrayList<BluetoothGattService>()
    private lateinit var powerAggregator: BatteryDataStatsAggregator
    private var serverConnection: GattServerConnection? = null
    private var serverCallback: GattServerCallback? = null
    private var clientCallback: GattClientCallback? = null
    private var peripheralScanner: PeripheralScanner? = null
    private lateinit var alwaysConnectedScanner: AlwaysConnectedScanner

    @JvmField
    @VisibleForTesting
    var aclListener: LowEnergyAclListener? = null

    private var appContext: Context? = null

    @JvmField
    @VisibleForTesting
    val isStarted = AtomicBoolean(false)

    /**
     * True only after [startServer] has opened a server. [isStarted] flips earlier, while the
     * open is still in flight, so listeners must not be told ready from that flag alone.
     */
    private val gattServerOpen = AtomicBoolean(false)

    /** Listeners that have already received [FitbitGattCallback.onFitbitGattReady]. */
    private val listenersNotifiedOfReady: MutableSet<FitbitGattCallback> =
        Collections.newSetFromMap(IdentityHashMap())

    private var connectionCleanup: Handler? = null
    private var asyncOperationThreadWatchdog: LooperWatchdog? = null
    private val fitbitGattAsyncOperationThread = HandlerThread("FitbitGatt Async Operation Thread", Thread.MAX_PRIORITY)
    private var fitbitGattAsyncOperationHandler: Handler? = null
    private var radioStatusListener: BluetoothRadioStatusListener? = null

    @JvmField
    @Volatile
    @VisibleForTesting
    var isBluetoothOn: Boolean = false

    private var extendedScanEnabled: Boolean = false
    private var advertiser: BitGattAdvertiser? = null

    private fun setup() {
        if (Timber.treeCount() == 0) {
            if (BuildConfig.DEBUG) {
                Timber.plant(BitgattDebugTree())
            } else {
                Timber.plant(BitgattReleaseTree())
            }
        }
        overallGattEventListeners = CopyOnWriteArrayList()
        alwaysConnectedScanner = AlwaysConnectedScanner(1, false, Looper.getMainLooper())
        powerAggregator = BatteryDataStatsAggregator(null)
        fitbitGattAsyncOperationThread.start()
        fitbitGattAsyncOperationHandler = Handler(fitbitGattAsyncOperationThread.looper)
        asyncOperationThreadWatchdog = LooperWatchdog(fitbitGattAsyncOperationThread.looper)
    }

    @VisibleForTesting
    fun setGattServer(gattServer: GattServerConnection?) {
        serverConnection = gattServer
    }

    /**
     * Re-reads the adapter. The [isBluetoothOn] field is what tests assert after a radio broadcast.
     */
    @JvmName("isBluetoothOn")
    fun bluetoothRadioIsOn(): Boolean {
        if (appContext == null) {
            Timber.w("Bitgatt must not be started yet, so as far as we know BT is off.")
            return false
        }
        val adapter = GattUtils().getBluetoothAdapter(appContext)
        if (adapter == null || !adapter.isEnabled) {
            if (isBluetoothOn) {
                isBluetoothOn = false
            }
        } else if (!isBluetoothOn) {
            isBluetoothOn = true
        }
        return isBluetoothOn
    }

    fun getFitbitGattAsyncOperationThread(): HandlerThread = fitbitGattAsyncOperationThread

    fun getAdvertiser(): BitGattAdvertiser {
        if (advertiser == null) {
            advertiser = BitGattAdvertiser(
                { getAppContext() },
                { bluetoothRadioIsOn() },
                { getServer() },
                { context -> adapterForAdvertiser(context) },
                { overallGattEventListeners }
            )
        }
        return advertiser!!
    }

    fun registerGattEventListener(callback: FitbitGattCallback) {
        if (!overallGattEventListeners.contains(callback)) {
            overallGattEventListeners.add(callback)
        }
    }

    fun unregisterGattEventListener(callback: FitbitGattCallback) {
        overallGattEventListeners.remove(callback)
        synchronized(listenersNotifiedOfReady) {
            listenersNotifiedOfReady.remove(callback)
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun unregisterAllGattEventListeners() {
        overallGattEventListeners.clear()
        synchronized(listenersNotifiedOfReady) {
            listenersNotifiedOfReady.clear()
        }
    }

    fun setStarted() {
        Timber.i("Initalization complete, start finished")
        val success = isStarted.compareAndSet(false, true)
        if (!success) {
            Timber.w("There was a problem updating the started state, are you starting from two threads?")
        } else {
            gattServerOpen.set(true)
            deliverGattReady()
        }
    }

    /**
     * Tells each registered listener that startup finished, once. Later [start] calls, including
     * the ones that find the stack already up, still reach listeners that registered after the
     * first callback.
     */
    private fun deliverGattReady() {
        val pending = ArrayList<FitbitGattCallback>()
        synchronized(listenersNotifiedOfReady) {
            for (callback in overallGattEventListeners) {
                if (listenersNotifiedOfReady.add(callback)) {
                    pending.add(callback)
                }
            }
        }
        for (callback in pending) {
            callback.onFitbitGattReady()
        }
    }

    private fun resetGattReady() {
        gattServerOpen.set(false)
        synchronized(listenersNotifiedOfReady) {
            listenersNotifiedOfReady.clear()
        }
    }

    fun setExtendedScanEnabled(enabled: Boolean) {
        extendedScanEnabled = enabled
        peripheralScanner?.setExtendedScanEnabled(enabled)
    }

    fun isExtendedScanEnabled(): Boolean = extendedScanEnabled

    fun dispatchScanFailure(failure: ScanFailure) {
        for (callback in overallGattEventListeners) {
            callback.onScanFailed(failure)
        }
    }

    fun startHighPriorityScan(context: Context?): Boolean {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return false
        }
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to start a high-priority scan, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return false
        }
        return scanner.startHighPriorityScan(context)
    }

    fun startPeriodicScan(context: Context?): Boolean {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return false
        }
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to start a periodical scan, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return false
        }
        return scanner.startPeriodicScan(context)
    }

    fun cancelScan(@Nullable context: Context?) {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to cancel a scan, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.cancelScan(context)
    }

    fun cancelPeriodicalScan(@Nullable context: Context?) {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to cancel a scan, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.cancelPeriodicalScan(context)
    }

    fun cancelHighPriorityScan(@Nullable context: Context?) {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to cancel a scan, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.cancelHighPriorityScan(context)
    }

    fun setScannerMockMode(mockMode: Boolean) {
        if (peripheralScanner == null) {
            Timber.w("You are trying to put the scanner into mock mode, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        alwaysConnectedScanner.setTestMode(mockMode)
    }

    fun setDeviceNameScanFilters(deviceNameFilters: List<String>?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to set device name filters on the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.setDeviceNameFilters(deviceNameFilters)
    }

    fun addDeviceNameScanFilter(deviceName: String?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to add a device name filter onto the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.addDeviceNameFilter(deviceName)
    }

    fun setScanServiceUuidFilters(uuidFilters: List<ParcelUuid>?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to set service uuid filters on the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.setServiceUuidFilters(uuidFilters)
    }

    fun addScanServiceUUIDWithMaskFilter(service: ParcelUuid?, mask: ParcelUuid?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to set service uuid with mask filters on the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.addServiceUUIDWithMask(service, mask)
    }

    fun addFilterUsingServiceData(serviceUUID: ParcelUuid?, serviceData: ByteArray?, serviceDataMask: ByteArray?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to add a filter using service data to the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.addFilterUsingServiceData(serviceUUID, serviceData, serviceDataMask)
    }

    fun addScanRssiFilter(minRssi: Int) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to set rssi filters on the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.addRssiFilter(minRssi)
    }

    fun addDeviceAddressFilter(deviceAddress: String?) {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to add a device address filter onto the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.addDeviceAddressFilter(deviceAddress)
    }

    fun getScanFilters(): List<ScanFilter> {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to get scan filters, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return Collections.emptyList()
        }
        return scanner.scanFilters
    }

    fun resetScanFilters() {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to reset the scan filters on the scanner, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return
        }
        scanner.resetFilters()
    }

    fun isScanning(): Boolean {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to determine the scan state, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return false
        }
        return scanner.isScanning()
    }

    fun isPendingIntentScanning(): Boolean {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to determine the scan state, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return false
        }
        return scanner.isPendingIntentScanning
    }

    fun isPeriodicalScanEnabled(): Boolean {
        val scanner = peripheralScanner
        if (scanner == null) {
            Timber.w("You are trying to determine the scan state, but the scanner isn't set-up, did you call FitbitGatt#start?")
            return false
        }
        return scanner.isPeriodicalScanEnabled
    }

    @WorkerThread
    @Synchronized
    fun startWithServicesAndScanFilters(
        @Nullable context: Context?,
        @Nullable services: List<BluetoothGattService>?,
        @Nullable filters: List<ScanFilter>?,
        callback: FitbitGattCallback?
    ) {
        if (context == null) {
            Timber.w("Can't start with a null context")
            return
        }
        if (!started()) {
            if (!startSimple(context)) {
                Timber.w("Couldn't start because BT was off")
                return
            }
            if (callback != null) {
                registerGattEventListener(callback)
            }
            val scanStarted = startScanWithFilters(filters, context)
            Timber.i("Did scan start? %b", scanStarted)
            startServer(context) { serverStarted ->
                if (!serverStarted) {
                    Timber.w("Could not get an instance of a gatt server, if you keep trying without fixing the issue, you might end up with too many server_if")
                    for (readCallback in overallGattEventListeners) {
                        readCallback.onFitbitGattStartFailed()
                    }
                    isStarted.set(false)
                    resetGattReady()
                    if (scanStarted) {
                        cancelPeriodicalScan(getAppContext())
                    }
                    return@startServer
                }
                if (services != null) {
                    Timber.v("Starting to add services, will set to started after complete")
                    if (services.isNotEmpty()) {
                        synchronized(servicesToAdd) {
                            servicesToAdd.addAll(services)
                            addServicesToGattServer()
                            return@startServer
                        }
                    }
                }
                val success = isStarted.compareAndSet(false, true)
                if (!success) {
                    Timber.w("There was a problem updating the started state, are you starting from two threads?")
                } else {
                    gattServerOpen.set(true)
                    deliverGattReady()
                }
            }
        } else if (gattServerOpen.get()) {
            Timber.v("Already started")
            deliverGattReady()
        } else {
            Timber.v("Already started")
        }
    }

    private fun startScanWithFilters(filters: List<ScanFilter>?, context: Context?): Boolean {
        if (filters != null) {
            val scanner = peripheralScanner
                ?: throw IllegalStateException("The peripheral scanner should not be null at this point, and you should not try to make this call with an empty or null filter list.")
            scanner.setScanFilters(filters)
            alwaysConnectedScanner.setScanFilters(filters)
            return scanner.startPeriodicScan(context)
        }
        Timber.w("Shame, you shouldn't be trying to start a scan with no filters!!!")
        return false
    }

    @WorkerThread
    @Synchronized
    fun startWithServices(context: Context, services: List<BluetoothGattService>?, callback: FitbitGattCallback?) {
        startWithServicesAndScanFilters(context, services, null, callback)
    }

    @WorkerThread
    @Synchronized
    fun startWithScanFilters(context: Context, scanFilters: List<ScanFilter>?, callback: FitbitGattCallback?) {
        startWithServicesAndScanFilters(context, null, scanFilters, callback)
    }

    @Synchronized
    fun start(context: Context) {
        if (!started()) {
            if (!startSimple(context)) {
                Timber.w("Couldn't start because BT was off")
                return
            }
            val success = isStarted.compareAndSet(false, true)
            if (!success) {
                Timber.w("There was a problem updating the started state, are you starting from two threads?")
            } else {
                startServer(context) { serverStarted ->
                    if (serverStarted) {
                        gattServerOpen.set(true)
                        deliverGattReady()
                    } else {
                        Timber.w("Could not get an instance of a gatt server")
                        for (readCallback in overallGattEventListeners) {
                            readCallback.onFitbitGattStartFailed()
                        }
                        isStarted.set(false)
                        resetGattReady()
                    }
                }
            }
        } else if (gattServerOpen.get()) {
            Timber.v("Already started")
            deliverGattReady()
        } else {
            Timber.v("Already started")
        }
    }

    @Synchronized
    private fun startSimple(context: Context): Boolean {
        Timber.v("Starting fitbit gatt")
        powerAggregator = BatteryDataStatsAggregator(context)
        appContext = context.applicationContext
        serverCallback = GattServerCallback()
        clientCallback = GattClientCallback()
        aclListener = LowEnergyAclListener()
        aclListener!!.register(appContext)
        connectionCleanup = Handler(context.mainLooper)
        peripheralScanner = PeripheralScanner(context, this)
        peripheralScanner!!.setExtendedScanEnabled(extendedScanEnabled)
        val adapter = GattUtils().getBluetoothAdapter(context)
        if (radioStatusListener == null) {
            radioStatusListener = BluetoothRadioStatusListener(getAppContext(), false)
        }
        radioStatusListener!!.startListening()
        radioStatusListener!!.setListener(this)
        isBluetoothOn = adapter != null && adapter.isEnabled
        if (!isBluetoothOn) {
            return false
        }
        Timber.v("Initializing the always connected scanner for one device, and that it should stop scanning when it finds one, if you wish to change this, please configure it.")
        addConnectedDevices(context)
        decrementAndInvalidateClosedConnections()
        asyncOperationThreadWatchdog?.startProbing()
        return true
    }

    @NonNull
    fun getAlwaysConnectedScanner(): AlwaysConnectedScanner = alwaysConnectedScanner

    @VisibleForTesting
    fun setBluetoothListener(listener: BluetoothRadioStatusListener?) {
        radioStatusListener = listener
    }

    @JvmName("isStarted")
    @Synchronized
    fun started(): Boolean = isStarted.get()

    @Nullable
    fun getAppContext(): Context? = appContext

    fun getServerCallback(): GattServerCallback? = serverCallback

    fun getClientCallback(): GattClientCallback? = clientCallback

    @Nullable
    fun getPeripheralScanner(): PeripheralScanner? {
        if (peripheralScanner == null) {
            Timber.w("The scanner is null, did you call FitbitGatt#start?")
        }
        return peripheralScanner
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Synchronized
    fun putConnectionIntoDevices(device: FitbitBluetoothDevice, conn: GattConnection) {
        if (!connectionMap.containsKey(device)) {
            connectionMap.put(device, conn)
            notifyListenersOfConnectionAdded(conn)
        }
    }

    @Synchronized
    fun isDeviceInConnections(device: FitbitBluetoothDevice): Boolean = connectionMap.containsKey(device)

    private fun notifyListenersOfConnectionAdded(connection: GattConnection) {
        for (callback in overallGattEventListeners) {
            callback.onBluetoothPeripheralDiscovered(connection)
        }
    }

    fun notifyListenersOfConnectionDisconnected(connection: GattConnection) {
        for (callback in overallGattEventListeners) {
            callback.onBluetoothPeripheralDisconnected(connection)
        }
    }

    fun registerGattServerListener(listener: GattServerListener) {
        serverCallback!!.addListener(listener)
    }

    fun unregisterGattServerListener(listener: GattServerListener) {
        serverCallback!!.removeListener(listener)
    }

    fun connectToScannedDevice(device: BluetoothDevice, callback: GattTransactionCallback) {
        connectToScannedDevice(FitbitBluetoothDevice(device), false, callback)
    }

    @VisibleForTesting
    fun connectToScannedDevice(fitDevice: FitbitBluetoothDevice, shouldMock: Boolean, callback: GattTransactionCallback) {
        var conn = connectionMap[fitDevice]
        if (conn == null) {
            val context = appContext
            if (context == null) {
                Timber.w("[%s] Bitgatt must not be started, please start bitgatt", fitDevice)
                return
            }
            conn = GattConnection(fitDevice, context.mainLooper)
            connectionMap.put(fitDevice, conn)
            notifyListenersOfConnectionAdded(conn)
        }
        conn.setMockMode(shouldMock)
        if (!conn.isConnected()) {
            val tx = GattConnectTransaction(conn, GattState.CONNECTED)
            conn.runTx(tx, callback)
        } else {
            val builder = TransactionResult.Builder()
            builder.resultStatus(TransactionResult.TransactionResultStatus.SUCCESS)
                .gattState(conn.getGattState())
            callback.onTransactionComplete(builder.build())
        }
    }

    fun getConnectionMap(): ConcurrentHashMap<FitbitBluetoothDevice, GattConnection> = connectionMap

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getNewlyScannedDevicesOnly(): List<FitbitBluetoothDevice> {
        val devices = ArrayList<FitbitBluetoothDevice>()
        for (iteratedDevice in getConnectionMap().keys) {
            if (iteratedDevice.origin == FitbitBluetoothDevice.DeviceOrigin.SCANNED) {
                devices.add(iteratedDevice)
            }
        }
        return devices
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun clearConnectionsMap() {
        connectionMap.clear()
    }

    private fun decrementAndInvalidateClosedConnections() {
        connectionCleanup!!.postDelayed({ doDecrementAndInvalidateClosedConnections() }, CLEANUP_INTERVAL)
    }

    @VisibleForTesting
    fun doDecrementAndInvalidateClosedConnections() {
        if (appContext == null) {
            Timber.w("[%s] Bitgatt must not be started, please start bitgatt.", Build.DEVICE)
            return
        }
        addConnectedDevices(appContext!!)
        for (fitbitBluetoothDevice in ArrayList(getConnectionMap().keys)) {
            val conn = getConnectionMap()[fitbitBluetoothDevice]
            if (conn != null && !conn.isConnected()) {
                val currentTtl = conn.getDisconnectedTTL()
                if (currentTtl <= 0) {
                    conn.close()
                    val connection = getConnectionMap().remove(fitbitBluetoothDevice)
                    if (connection != null) {
                        notifyListenersOfConnectionDisconnected(connection)
                        Timber.v("Connection for %s is disconnected and pruned", connection.getDevice())
                    }
                } else {
                    conn.setDisconnectedTTL(currentTtl - CLEANUP_INTERVAL)
                }
            }
        }
        decrementAndInvalidateClosedConnections()
    }

    @Synchronized
    fun addScannedDevice(device: FitbitBluetoothDevice) {
        val conn = connectionMap[device]
        if (conn == null) {
            val context = appContext
            if (context == null) {
                Timber.w("[%s] Bitgatt must not be started, please start bitgatt", device)
                return
            }
            Timber.v("Adding scanned device %s", device.toString())
            val created = GattConnection(device, context.mainLooper)
            device.origin = FitbitBluetoothDevice.DeviceOrigin.SCANNED
            connectionMap.put(device, created)
            notifyListenersOfConnectionAdded(created)
        } else {
            val oldDevice = conn.getDevice()
            val previousDeviceName = oldDevice.getName()
            val previousScanRecord = oldDevice.getScanRecord()
            val previousRssi = oldDevice.getRssi()
            if (!previousDeviceName.equals(device.getName())) {
                Timber.w("This device has the same mac (bluetooth ID) as a known device, but has changed it's BT name, IRL be careful this can break upstream logic, or have security implications.")
            }
            oldDevice.origin = FitbitBluetoothDevice.DeviceOrigin.SCANNED
            val nextRecord = device.getScanRecord()
            if (!previousDeviceName.equals(device.getName()) ||
                previousRssi != device.getRssi() ||
                (previousScanRecord != null && nextRecord != null &&
                    !Arrays.equals(previousScanRecord.getBytes(), nextRecord.getBytes()))
            ) {
                Timber.v("Found device may have changed was %s, and now is %s", oldDevice, device)
                oldDevice.setName(device.getName())
                oldDevice.setScanRecord(device.getScanRecord())
                oldDevice.setRssi(device.getRssi())
            }
            notifyListenersOfConnectionAdded(conn)
        }
    }

    private fun addConnectedDevices(context: Context) {
        Timber.v("Adding connected or bonded devices")
        val missingConnect = GattUtils.missingConnectPermission(context)
        if (missingConnect != null) {
            Timber.w("Skipping bonded and connected devices, missing %s", missingConnect)
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (manager != null) {
            val adapter = manager.adapter
            if (adapter != null) {
                val bondedDevices = adapter.bondedDevices
                for (bondedDevice in bondedDevices) {
                    val fitBluetoothDevice = FitbitBluetoothDevice(bondedDevice)
                    fitBluetoothDevice.origin = FitbitBluetoothDevice.DeviceOrigin.BONDED
                    if (connectionMap[fitBluetoothDevice] == null) {
                        val contextForConnection = appContext
                        if (contextForConnection == null) {
                            Timber.w("[%s] Bitgatt must not be started, please start bitgatt", fitBluetoothDevice)
                            return
                        }
                        val conn = GattConnection(fitBluetoothDevice, contextForConnection.mainLooper)
                        Timber.v("Adding bonded device named %s, with address %s", bondedDevice.name, bondedDevice.address)
                        connectionMap.put(fitBluetoothDevice, conn)
                        notifyListenersOfConnectionAdded(conn)
                    }
                }
                val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
                for (connectedDevice in connectedDevices) {
                    val fitbitBluetoothDevice = FitbitBluetoothDevice(connectedDevice)
                    fitbitBluetoothDevice.origin = FitbitBluetoothDevice.DeviceOrigin.CONNECTED
                    if (connectionMap[fitbitBluetoothDevice] == null) {
                        Timber.v("Adding connected device named %s, with address %s", connectedDevice.name, connectedDevice.address)
                        val contextForConnection = appContext
                        if (contextForConnection != null) {
                            val conn = GattConnection(fitbitBluetoothDevice, contextForConnection.mainLooper)
                            connectionMap.put(fitbitBluetoothDevice, conn)
                            notifyListenersOfConnectionAdded(conn)
                        } else {
                            Timber.w("Tried to add a discovered device, but the cached context was null")
                        }
                    }
                }
            }
        }
        Timber.v("Added all connected or bonded devices")
    }

    fun getMatchingConnectionsForDeviceNames(@Nullable names: List<String>?): List<GattConnection> {
        val connections = ArrayList<GattConnection>(2)
        if (names == null) {
            connections.addAll(connectionMap.values)
            return connections
        }
        for (name in names) {
            val fitbitDeviceEnumeration = connectionMap.keys()
            while (fitbitDeviceEnumeration.hasMoreElements()) {
                val device = fitbitDeviceEnumeration.nextElement()
                if (device.getName().equals(name)) {
                    val conn = connectionMap[device]
                    if (conn != null) {
                        connections.add(conn)
                    }
                }
            }
        }
        return connections
    }

    fun getMatchingConnectionsForServices(@Nullable services: List<UUID>?): List<GattConnection> {
        val connections = ArrayList<GattConnection>(2)
        if (services == null) {
            connections.addAll(connectionMap.values)
            return connections
        }
        val fitbitDeviceEnumeration = connectionMap.keys()
        while (fitbitDeviceEnumeration.hasMoreElements()) {
            val device = fitbitDeviceEnumeration.nextElement()
            val conn = connectionMap[device]
            if (conn != null) {
                val gatt = conn.getGatt()
                if ((conn.getMockMode() || gatt != null) && conn.isConnected()) {
                    for (serviceUuid in services) {
                        if (conn.connectedDeviceHostsService(serviceUuid)) {
                            connections.add(conn)
                        }
                    }
                }
            }
        }
        return connections
    }

    @Nullable
    fun getConnectionForBluetoothAddress(bluetoothAddress: String?): GattConnection? {
        if (appContext != null) {
            return getConnectionForAddress(bluetoothAddress)
        }
        Timber.w("Error getting connection FitbitGatt start state %s", started())
        return null
    }

    @Deprecated("Using this method is discouraged as it will soon become private")
    @Nullable
    fun getConnectionForBluetoothAddress(context: Context, bluetoothAddress: String?): GattConnection? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (mgr != null) {
            val adp = mgr.adapter
            if (adp != null) {
                val bluetoothDevice = adp.getRemoteDevice(bluetoothAddress)
                return getConnection(bluetoothDevice)
            }
            Timber.e("Couldn't fetch the connection because we couldn't initialize the adapter")
            return null
        }
        Timber.e("Couldn't fetch the connection because we couldn't initialize the manager")
        return null
    }

    private fun interface OpenGattServerCallback {
        fun onGattServerStatus(started: Boolean)
    }

    @Synchronized
    private fun startServer(context: Context, callback: OpenGattServerCallback) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (manager != null && manager.adapter != null) {
            val missingConnect = GattUtils.missingConnectPermission(context)
            if (missingConnect != null) {
                Timber.w("openGattServer refused, missing %s", missingConnect)
                callback.onGattServerStatus(false)
                return
            }
            val openServer = Runnable {
                for (openServerRetryCount in 0 until OPEN_GATT_SERVER_RETRY_COUNT) {
                    val adapter = manager.adapter
                    if (adapter == null || !adapter.isEnabled) {
                        Timber.w("Bluetooth was turned off while attempting to start server")
                        callback.onGattServerStatus(false)
                        return@Runnable
                    }
                    gattServer = manager.openGattServer(context, serverCallback)
                    if (gattServer != null) {
                        if (advertiser != null) {
                            advertiser!!.stop()
                        }
                        val existing = serverConnection
                        if (existing != null) {
                            val clearServices = ClearServerServicesTransaction(
                                existing,
                                GattState.CLEAR_GATT_SERVER_SERVICES_SUCCESS
                            )
                            existing.runTx(clearServices) { result ->
                                Timber.v("The gatt server services were cleared %s", result.resultStatus)
                                replaceGattServerConnection(context, callback)
                            }
                            return@Runnable
                        } else {
                            gattServer!!.clearServices()
                            replaceGattServerConnection(context, callback)
                            return@Runnable
                        }
                    }
                }
                Timber.w("Exhausted retries to open gatt server, recommend that you tell your user to clear bluetooth share in the apps list, the GATT db is probably corrupt")
                callback.onGattServerStatus(false)
            }
            var posted = false
            try {
                posted = fitbitGattAsyncOperationHandler!!.postDelayed(openServer, GATT_SERVER_START_FAILURE_RETRY_INTERVAL)
            } catch (ignored: RuntimeException) {
                posted = false
            }
            if (!posted) {
                openServer.run()
            }
        } else {
            Timber.w("No bluetooth manager, we must be simulating, or BT is off!!!")
            callback.onGattServerStatus(false)
        }
    }

    private fun replaceGattServerConnection(context: Context, callback: OpenGattServerCallback) {
        serverConnection?.close()
        serverConnection = GattServerConnection(gattServer, context.mainLooper)
        serverConnection!!.setState(GattState.IDLE)
        callback.onGattServerStatus(true)
        Timber.v("Gatt server successfully opened")
    }

    @Synchronized
    fun addBackgroundScannedDeviceConnection(@Nullable device: FitbitBluetoothDevice?) {
        if (device != null) {
            device.origin = FitbitBluetoothDevice.DeviceOrigin.SCANNED
            addScannedDevice(device)
        } else {
            Timber.w("No result provided.")
        }
    }

    @Synchronized
    fun addBackgroundScannedDeviceConnection(@Nullable result: ScanResult?) {
        if (result != null) {
            val bluetoothDevice = result.device
            val device = FitbitBluetoothDevice(bluetoothDevice)
            device.origin = FitbitBluetoothDevice.DeviceOrigin.SCANNED
            device.setRssi(result.rssi)
            addScannedDevice(device)
        } else {
            Timber.w("No result provided.")
        }
    }

    @Nullable
    fun startBackgroundScan(
        @NonNull context: Context,
        @NonNull broadcastIntent: Intent,
        @NonNull macAddresses: List<String>
    ): PendingIntent? {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return null
        }
        val scanner = peripheralScanner
        if (started() && scanner != null) {
            return scanner.startBackgroundScan(macAddresses, broadcastIntent, context)
        }
        Timber.w("The FitbitGatt must have been started in order to use the background scanner.")
        return null
    }

    fun startSystemManagedPendingIntentScan(@NonNull context: Context, @NonNull scanFilters: List<ScanFilter>): Boolean {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return false
        }
        val scanner = peripheralScanner
        if (started() && scanner != null) {
            return scanner.startPendingIntentBasedBackgroundScan(scanFilters, context)
        }
        Timber.i("Can't start because we aren't started, or the scanner is null")
        return false
    }

    fun stopSystemManagedPendingIntentScan() {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        val scanner = peripheralScanner
        if (started() && scanner != null) {
            try {
                scanner.cancelPendingIntentBasedBackgroundScan()
            } catch (error: NoSuchMethodError) {
                Timber.i(error, "There was a no such method error stopping the pending intent scan, assuming stopped.")
            }
        } else {
            Timber.i("Can't stop because we aren't started, or the scanner is null")
        }
    }

    fun stopBackgroundScan(@Nullable pendingIntent: PendingIntent?) {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        if (pendingIntent == null) {
            Timber.v("No pending intent.")
        } else {
            val scanner = peripheralScanner
            if (scanner != null) {
                scanner.stopBackgroundScan(pendingIntent)
            } else {
                Timber.w("Peripheral scanner was null, did you forget to call FitbitGatt#start?")
            }
        }
    }

    fun stopBackgroundScanWithRegularIntent(context: Context, @Nullable regularIntent: Intent?) {
        if (alwaysConnectedScanner.isAlwaysConnectedScannerEnabled) {
            Timber.i("You are using the always connected scanner, stop it first before ad-hoc scanning")
            return
        }
        if (regularIntent == null) {
            Timber.v("No intent.")
        } else {
            val pending: PendingIntent? = try {
                ScanIntents.getBroadcast(context, regularIntent)
            } catch (error: NoSuchMethodError) {
                Timber.i(error, "There was a no such method error stopping the pending intent scan, assuming stopped")
                return
            }
            val scanner = peripheralScanner
            if (scanner != null && pending != null) {
                scanner.stopBackgroundScan(pending)
            } else {
                Timber.w("Peripheral scanner was null, did you forget to call FitbitGatt#start?")
            }
        }
    }

    fun getServer(): GattServerConnection? = serverConnection

    @Nullable
    fun getConnection(@Nullable device: BluetoothDevice?): GattConnection? {
        if (device == null || device.address == null) {
            return null
        }
        return getConnectionForAddress(device.address)
    }

    @Nullable
    fun getConnectionForAddress(@Nullable bluetoothAddress: String?): GattConnection? {
        if (bluetoothAddress == null) {
            return null
        }
        for (device in connectionMap.keys) {
            if (device.getAddress().equals(bluetoothAddress)) {
                return connectionMap[device]
            }
        }
        return null
    }

    @Nullable
    fun getConnection(device: FitbitBluetoothDevice?): GattConnection? {
        if (device == null) {
            return null
        }
        return connectionMap[device]
    }

    @TargetApi(24)
    protected fun getAdapter(context: Context?): BluetoothAdapter? {
        if (atLeastSDK(Build.VERSION_CODES.M) && context != null) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            return manager?.adapter
        }
        return BluetoothAdapter.getDefaultAdapter()
    }

    private fun adapterForAdvertiser(context: Context?): BluetoothAdapter? {
        val adapter = getAdapter(context)
        if (adapter != null || context == null || atLeastSDK(Build.VERSION_CODES.M)) {
            return adapter
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return manager?.adapter
    }

    fun getBluetoothDevice(macAddress: String?): BluetoothDevice? {
        val adapter = getAdapter(appContext)
        if (adapter != null && BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            return adapter.getRemoteDevice(macAddress)
        }
        return null
    }

    override fun onScanStatusChanged(isScanning: Boolean) {
        Timber.i("On scan status changed %b", isScanning)
        if (isScanning) {
            for (callback in overallGattEventListeners) {
                callback.onScanStarted()
            }
        } else {
            for (callback in overallGattEventListeners) {
                callback.onScanStopped()
            }
        }
    }

    override fun onPendingIntentScanStatusChanged(isScanning: Boolean) {
        if (isScanning) {
            for (callback in overallGattEventListeners) {
                callback.onPendingIntentScanStarted()
            }
        } else {
            for (callback in overallGattEventListeners) {
                callback.onPendingIntentScanStopped()
            }
        }
    }

    override fun onFitbitDeviceFound(device: FitbitBluetoothDevice) {
        addScannedDevice(device)
    }

    private fun addServicesToGattServer() {
        synchronized(servicesToAdd) {
            val serviceIterator = servicesToAdd.listIterator()
            val addGattServerServiceTransactions = ArrayList<GattServerTransaction>(servicesToAdd.size)
            while (serviceIterator.hasNext()) {
                val transaction = AddGattServerServiceTransaction(getServer(), GattState.ADD_SERVICE_SUCCESS, serviceIterator.next())
                addGattServerServiceTransactions.add(transaction)
                serviceIterator.remove()
            }
            val compositeServerTransaction = CompositeServerTransaction(serverConnection, addGattServerServiceTransactions)
            getServer()!!.runTx(compositeServerTransaction) { result ->
                if (result.resultStatus == TransactionResult.TransactionResultStatus.FAILURE) {
                    Timber.w("We failed in adding gatt server services, so we will clear to rollback our changes, the details were: %s", result)
                    val clearServerServices = ClearServerServicesTransaction(serverConnection, GattState.CLEAR_GATT_SERVER_SERVICES_SUCCESS)
                    serverConnection!!.runTx(clearServerServices) { clearResult ->
                        Timber.v("The clear services result after failure was %s", clearResult)
                    }
                }
                Timber.d("Gatt server init add service result: %s", result)
            }
        }
    }

    private fun cleanUpBecauseBluetoothIsTurningOff() {
        for (entry in getConnectionMap().entries) {
            cleanUpConnection(entry.value)
        }
        val scanner = getPeripheralScanner()
        if (scanner != null) {
            if (scanner.isPendingIntentScanning() || scanner.isScanning()) {
                scanner.cancelPendingIntentBasedBackgroundScan()
                scanner.cancelScan(appContext)
            }
        }
    }

    private fun cleanUpConnection(conn: GattConnection) {
        conn.cleanUpConnection()
        conn.justClearGatt()
        conn.setState(GattState.BT_OFF)
    }

    private fun switchAllConnectionsToDisconnectedBecauseBtIsOn() {
        for (entry in getConnectionMap().entries) {
            entry.value.setState(GattState.DISCONNECTED)
        }
    }

    override fun bluetoothOff() {
        isBluetoothOn = false
        Timber.v("Bluetooth is off")
        if (advertiser != null) {
            advertiser!!.stop()
        }
        cleanUpBecauseBluetoothIsTurningOff()
        for (callback in overallGattEventListeners) {
            callback.onBluetoothOff()
        }
    }

    override fun bluetoothOn() {
        isBluetoothOn = true
        val context = appContext
        if (context == null) {
            Timber.e("Refreshing the gatt server after BT was enabled failed")
            return
        }
        startServer(context) { serverStarted ->
            if (serverStarted) {
                Timber.v("Gatt server up and ready")
            } else {
                Timber.w("After several attempts the gatt server could not be re-opened, tread lightly")
            }
            peripheralScanner?.recycleLeScanner()
            Timber.v("Bluetooth is on")
            switchAllConnectionsToDisconnectedBecauseBtIsOn()
            for (callback in overallGattEventListeners) {
                callback.onBluetoothOn()
            }
        }
    }

    override fun bluetoothTurningOff() {
        isBluetoothOn = false
        // Stop advertising before the Samsung clear-services strategy runs. That strategy
        // posts clearServices() and must not see a live advertiser.
        if (advertiser != null) {
            advertiser!!.stop()
        }
        val strategyDevice = AndroidDevice.Builder().manufacturerName("Samsung").build()
        val executableStrategy = StrategyProvider().getStrategyForPhoneAndGattConnection(
            strategyDevice,
            null,
            Situation.CLEAR_GATT_SERVER_SERVICES_DEVICE_FUNKY_BT_IMPL
        )
        if (executableStrategy is BluetoothOffClearGattServerStrategy) {
            executableStrategy.applyStrategy()
        }
        Timber.v("Bluetooth is turning off")
        for (callback in overallGattEventListeners) {
            callback.onBluetoothTurningOff()
        }
        cleanUpBecauseBluetoothIsTurningOff()
    }

    override fun bluetoothTurningOn() {
        isBluetoothOn = false
        Timber.v("Bluetooth is turning on")
        for (callback in overallGattEventListeners) {
            callback.onBluetoothTurningOn()
        }
    }

    companion object {
        @JvmField
        val MAX_TTL: Long = TimeUnit.HOURS.toMillis(1)

        private const val GATT_SERVER_START_FAILURE_RETRY_INTERVAL = 500L
        private const val OPEN_GATT_SERVER_RETRY_COUNT = 3
        private val CLEANUP_INTERVAL = TimeUnit.MINUTES.toMillis(5)

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var ourInstance: FitbitGatt? = null

        @JvmStatic
        fun getInstance(): FitbitGatt {
            if (ourInstance == null) {
                synchronized(FitbitGatt::class.java) {
                    if (ourInstance == null) {
                        val created = FitbitGatt()
                        ourInstance = created
                        created.setup()
                    }
                }
            }
            return ourInstance!!
        }

        @JvmStatic
        fun atLeastSDK(buildVersion: Int): Boolean = Build.VERSION.SDK_INT >= buildVersion
    }
}
