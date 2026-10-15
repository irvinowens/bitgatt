/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import com.fitbit.bluetooth.fbgatt.engine.Admit
import com.fitbit.bluetooth.fbgatt.engine.ConnectionEngine
import com.fitbit.bluetooth.fbgatt.util.GattStatus
import com.fitbit.bluetooth.fbgatt.util.GattUtils
import java.io.Closeable
import java.util.ArrayList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Synchronized
import timber.log.Timber

/**
 * One remote device. [GattConnection.GattConnection] `(device, looper)` stays the public constructor.
 * Direct connect stays `autoConnect` false on every API band.
 */
open class GattConnection(
    private val device: FitbitBluetoothDevice,
    mainLooper: Looper
) : Closeable {
    private val disconnectedTTL = AtomicLong(FitbitGatt.MAX_TTL)
    @Nullable
    @Volatile
    private var gatt: BluetoothGatt? = null
    private var state: GattState = GattState.DISCONNECTED
    private val callbackLooper: Looper = mainLooper
    private val asynchronousEventListeners = ArrayList<ConnectionEventListener>(1)
    private var mockMode: Boolean = false
    private val mockServices = ArrayList<BluetoothGattService>(1)
    private val clientQueue: TransactionQueueController = TransactionQueueController(this)
    private val intraTransactionDelay = AtomicLong(0)
    @NonNull
    private val mainHandler: Handler = Handler(mainLooper)

    init {
        clientQueue.start()
    }

    fun getDisconnectedTTL(): Long = disconnectedTTL.get()

    fun resetDisconnectedTTL() {
        disconnectedTTL.set(FitbitGatt.MAX_TTL)
    }

    fun setDisconnectedTTL(value: Long) {
        disconnectedTTL.set(value)
    }

    @VisibleForTesting
    fun numberOfEventListeners(): Int = asynchronousEventListeners.size

    fun setIntraTransactionDelay(txDelay: Long) {
        val oldValue = intraTransactionDelay.getAndSet(txDelay)
        Timber.v("[%s] Changing intra-transaction delay from %dms, to %dms", getDevice(), oldValue, intraTransactionDelay.get())
    }

    fun getIntraTransactionDelay(): Long = intraTransactionDelay.get()

    fun registerConnectionEventListener(eventListener: ConnectionEventListener) {
        if (!asynchronousEventListeners.contains(eventListener)) {
            asynchronousEventListeners.add(eventListener)
        } else {
            Timber.v("[%s] This listener is already registered", getDevice())
        }
    }

    fun unregisterConnectionEventListener(eventListener: ConnectionEventListener) {
        if (asynchronousEventListeners.isEmpty()) {
            Timber.v("[%s] There are no event listeners to remove", getDevice())
            return
        }
        asynchronousEventListeners.remove(eventListener)
    }

    fun getConnectionEventListeners(): ArrayList<ConnectionEventListener> {
        val copy = ArrayList<ConnectionEventListener>(asynchronousEventListeners.size)
        copy.addAll(asynchronousEventListeners)
        return copy
    }

    fun registerGattClientListener(clientListener: GattClientListener) {
        val callback = FitbitGatt.getInstance().getClientCallback()
        if (callback != null) {
            callback.addListener(clientListener)
        } else {
            Timber.w("[%s] The client callback was null, something is quite wrong", getDevice())
        }
    }

    fun unregisterGattClientListener(clientListener: GattClientListener) {
        val callback = FitbitGatt.getInstance().getClientCallback()
        if (callback != null) {
            callback.removeListener(clientListener)
        } else {
            Timber.w("[%s] The client callback was null, something is quite wrong", getDevice())
        }
    }

    @Nullable
    fun getGatt(): BluetoothGatt? = gatt

    @Synchronized
    fun setState(state: GattState) {
        val previous = this.state
        Timber.v("[%s] Transitioning from state %s to state %s", getDevice(), previous.name, state.name)
        this.state = state
        if (state == GattState.CONNECTING || previous == GattState.CONNECTING) {
            tellScannerAboutConnecting()
        }
    }

    private fun tellScannerAboutConnecting() {
        val scanner = FitbitGatt.getInstance().getPeripheralScanner() ?: return
        var connecting = this.state == GattState.CONNECTING
        if (!connecting) {
            for (connection in FitbitGatt.getInstance().getConnectionMap().values) {
                if (connection.getGattState() == GattState.CONNECTING) {
                    connecting = true
                    break
                }
            }
        }
        scanner.setDeferScanWhileConnecting(connecting)
    }

    @Synchronized
    fun getGattState(): GattState = state

    fun getDevice(): FitbitBluetoothDevice = device

    @Synchronized
    fun checkTransaction(tx: GattTransaction): Admit.GuardState {
        return Admit.check(getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState())
    }

    fun resetStates() {
        setState(GattState.DISCONNECTED)
    }

    @VisibleForTesting
    fun addService(service: BluetoothGattService) {
        mockServices.add(service)
    }

    fun connectedDeviceHostsService(serviceUuid: UUID): Boolean {
        if (mockMode) {
            for (service in mockServices) {
                if (service.uuid != null && service.uuid == serviceUuid) {
                    return true
                }
            }
            return false
        }
        if (isConnected()) {
            val localGatt = getGatt()
            return localGatt != null && localGatt.getService(serviceUuid) != null
        }
        return false
    }

    @Nullable
    fun getRemoteGattServiceCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic? {
        val service = getRemoteGattService(serviceUuid) ?: return null
        return service.getCharacteristic(characteristicUuid)
    }

    @Nullable
    fun getRemoteGattService(uuid: UUID): BluetoothGattService? {
        if (mockMode) {
            for (service in mockServices) {
                if (uuid == service.uuid) {
                    return service
                }
            }
            return null
        }
        val localGatt = gatt
        return if (isConnected() && localGatt != null) {
            localGatt.getService(uuid)
        } else {
            null
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun simulateDisconnect() {
        if (mockMode) {
            setState(GattState.DISCONNECTED)
            for (asyncConnListener in getConnectionEventListeners()) {
                asyncConnListener.onClientConnectionStateChanged(
                    TransactionResult.Builder()
                        .resultStatus(TransactionResult.TransactionResultStatus.FAILURE)
                        .gattState(getGattState())
                        .responseStatus(GattStatus.GATT_UNKNOWN.ordinal)
                        .build(),
                    this
                )
            }
        } else {
            throw IllegalStateException(
                String.format(Locale.ENGLISH, "[%s] You can't simulate a disconnection if you are not in mock mode", getDevice())
            )
        }
    }

    @VisibleForTesting
    fun setMockMode(mockMode: Boolean) {
        this.mockMode = mockMode
    }

    @Synchronized
    fun connect(): Boolean {
        if (connectionInstanceAlreadyExists(getDevice())) {
            Timber.d("[%s] GattConnection already exists for the device", getDevice())
            return false
        }
        return if (gatt == null) {
            Timber.v("[%s] Android BluetoothGatt was null, start a new Android BluetoothGatt instance connect to device", device)
            ifGattHasNeverBeenInstantiatedConnect(device)
        } else {
            Timber.v("[%s] Android BluetoothGatt has been used before, using an existing Android BluetoothGatt instance to connect to device", device)
            ifGattHasBeenInstantiatedConnect(device)
        }
    }

    private fun connectionInstanceAlreadyExists(device: FitbitBluetoothDevice): Boolean {
        val cachedConnection = FitbitGatt.getInstance().getConnectionMap()[device]
        if (cachedConnection != null && cachedConnection != this) {
            Timber.w("[%s] You are trying to connect but there is already a connection available for it.  We are saving you from creating too many client_ifs #developerlove.", device)
            return true
        } else if (cachedConnection != null && cachedConnection.getDevice() == device) {
            Timber.w("[%s] While this instance isn't in the cache, there is already a connection in the queue, please be careful not to create too many client_ifs #developerlove.", device)
            return false
        }
        return false
    }

    private fun ifGattHasBeenInstantiatedConnect(device: FitbitBluetoothDevice): Boolean {
        if (isConnected()) {
            Timber.w("[%s] You can't connect while connected, or connecting, please know what you are doing here.", device)
            return true
        }
        setState(GattState.CONNECTING)
        if (mockMode) {
            mockConnect()
            return true
        }
        if (refuseMissingConnectPermission(device)) {
            setState(GattState.FAILURE_CONNECTING)
            return false
        }
        val localGatt = gatt
        var success = false
        if (localGatt != null) {
            success = localGatt.connect()
        }
        if (!success) {
            setState(GattState.FAILURE_CONNECTING)
        }
        return success
    }

    private fun refuseMissingConnectPermission(device: FitbitBluetoothDevice): Boolean {
        val missing = GattUtils.missingConnectPermission(FitbitGatt.getInstance().getAppContext())
        if (missing == null) {
            return false
        }
        Timber.w("[%s] Connect refused, missing %s", device, missing)
        return true
    }

    private fun ifGattHasNeverBeenInstantiatedConnect(device: FitbitBluetoothDevice): Boolean {
        if (mockMode) {
            mockConnect()
            return true
        }
        if (refuseMissingConnectPermission(device)) {
            setState(GattState.FAILURE_CONNECTING)
            return false
        }
        setState(GattState.CONNECTING)
        gatt = openDirectGatt(device)
        if (gatt == null) {
            setState(GattState.FAILURE_CONNECTING)
            return false
        }
        return true
    }

    /**
     * Direct connect (`autoConnect` false) on every API. API 37 uses the settings overload
     * and a non-main executor. The PHY mask on API 26–36 is the platform default, `PHY_LE_1M_MASK`.
     */
    private fun openDirectGatt(device: FitbitBluetoothDevice): BluetoothGatt? {
        val context = FitbitGatt.getInstance().getAppContext()
        val callback: BluetoothGattCallback = FitbitGatt.getInstance().getClientCallback()!!
        val remote = device.btDevice
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= Build.VERSION_CODES.CINNAMON_BUN) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            return remote.connectGatt(settings, DIRECT_CONNECT_EXECUTOR, callback)
        }
        if (sdk >= Build.VERSION_CODES.O) {
            return remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
        }
        if (sdk >= Build.VERSION_CODES.M) {
            return remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        return remote.connectGatt(context, false, callback)
    }

    private fun mockConnect() {
        Timber.i("[%s] Mock connecting!!!!", getDevice())
        setState(GattState.CONNECTING)
        mainHandler.postDelayed({
            FitbitGatt.getInstance().getClientCallback()!!
                .onConnectionStateChange(null, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }, 1499)
        FitbitGatt.getInstance().putConnectionIntoDevices(device, this)
    }

    private fun mockDisconnect() {
        Timber.i("[%s] Mock disconnecting!!!", getDevice())
        setState(GattState.DISCONNECTING)
        mainHandler.postDelayed({
            FitbitGatt.getInstance().getClientCallback()!!
                .onConnectionStateChange(null, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        }, 150)
    }

    fun disconnect() {
        if (mockMode) {
            mockDisconnect()
            return
        }
        val localGatt = gatt
        if (localGatt == null) {
            setState(GattState.DISCONNECTED)
        } else {
            try {
                localGatt.disconnect()
            } catch (e: NullPointerException) {
                Timber.e(e, "[%s] OS Stack Failure while disconnecting", getDevice())
            }
            setState(GattState.DISCONNECTING)
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Synchronized
    fun finish() {
        val localGatt = gatt
        if (localGatt != null) {
            closeClientIf(localGatt)
            gatt = null
        }
        clientQueue.stop()
    }

    fun justClearGatt() {
        val localGatt = gatt
        if (localGatt != null) {
            closeClientIf(localGatt)
            gatt = null
        }
    }

    fun cleanUpConnection() {
        Timber.v("[%s] Cleaning up connection, flushing pending gatt operations", getDevice())
        clientQueue.clearQueue()
    }

    fun isConnected(): Boolean {
        val current = getGattState()
        return current != GattState.DISCONNECTED &&
            current != GattState.DISCONNECTING &&
            current != GattState.BT_OFF &&
            current != GattState.CONNECTING
    }

    fun runTx(transaction: GattTransaction, callback: GattTransactionCallback) {
        Timber.v("[%s] Received transaction: %s", getDevice(), transaction.getName())
        resetDisconnectedTTL()
        if (intraTransactionDelay.get() == 0L) {
            queueTransaction(transaction, callback)
        } else {
            val currentTimeMillis = System.currentTimeMillis()
            Timber.v("[%s] Posting tx to queue in %dms", getDevice(), intraTransactionDelay.get())
            getMainHandler().postDelayed({
                val queueTimeMillis = System.currentTimeMillis()
                val timeToQueue = queueTimeMillis - currentTimeMillis
                Timber.v("[%s] Queueing tx %dms after posting", getDevice(), timeToQueue)
                queueTransaction(transaction, callback)
            }, intraTransactionDelay.get())
        }
    }

    private fun queueTransaction(transaction: GattTransaction, callback: GattTransactionCallback) {
        clientQueue.engine().queueTransaction {
            transaction.commit { result ->
                ConnectionEngine.deliverOnMainLooper(callbackLooper, mainHandler) {
                    callback.onTransactionComplete(result)
                }
            }
        }
    }

    fun getClientTransactionQueueController(): TransactionQueueController = clientQueue

    fun getMockMode(): Boolean = mockMode

    @NonNull
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("Object.toString(): ")
        builder.append(super.toString())
        builder.append(" device: ")
        builder.append(getDevice())
        builder.append(" isConnected: ")
        builder.append(isConnected())
        builder.append(" state: ")
        val gattState = getGattState()
        builder.append(gattState)
        builder.append(" state type: ")
        builder.append(gattState.stateType)
        builder.append(" numConnEvtListeners ")
        builder.append(getConnectionEventListeners().size)
        return builder.toString()
    }

    @Synchronized
    fun gattRelease() {
        val localGatt = gatt
        if (localGatt != null) {
            closeClientIf(localGatt)
            gatt = null
        } else {
            Timber.w("[%s] The gatt was null when trying to release, the logic is busted or you are suffering from an Android bug, look into a strategy.", getDevice())
        }
    }

    @NonNull
    fun getMainHandler(): Handler = mainHandler

    override fun close() {
        finish()
    }

    private fun closeClientIf(localGatt: BluetoothGatt) {
        try {
            localGatt.close()
        } catch (e: NullPointerException) {
            Timber.e(e, "[%s] Ran into OS Stack NPE", getDevice())
        }
    }

    companion object {
        private val DIRECT_CONNECT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BitGatt connectGatt").apply { isDaemon = true }
        }
    }
}
