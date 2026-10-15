/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import com.fitbit.bluetooth.fbgatt.engine.Admit
import com.fitbit.bluetooth.fbgatt.engine.ConnectionEngine
import java.io.Closeable
import java.util.ArrayList
import java.util.HashSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Synchronized
import timber.log.Timber

/**
 * The single local GATT server connection held by [FitbitGatt].
 */
open class GattServerConnection(
    @Nullable private var server: BluetoothGattServer?,
    looper: Looper
) : Closeable {
    private var serverQueue: TransactionQueueController? = TransactionQueueController()
    private var state: GattState = GattState.IDLE
    private val intraTransactionDelay = AtomicLong(0)
    private val callbackLooper: Looper = looper
    private val asynchronousEventListeners = ArrayList<ServerConnectionEventListener>(1)
    private val connectedDevices = HashSet<FitbitBluetoothDevice>()
    private val mainHandler: Handler = Handler(looper)
    private var mockMode: Boolean = false

    init {
        serverQueue?.start()
    }

    @Synchronized
    fun getGattState(): GattState = state

    fun getServer(): BluetoothGattServer? = server

    @Synchronized
    fun checkTransaction(tx: GattTransaction): Admit.GuardState {
        return Admit.check(getGattState(), tx.successState, tx.admitsRegardlessOfState())
    }

    fun registerConnectionEventListener(eventListener: ServerConnectionEventListener) {
        if (!asynchronousEventListeners.contains(eventListener)) {
            asynchronousEventListeners.add(eventListener)
        } else {
            Timber.v("[%s] This listener is already registered", Build.MODEL)
        }
    }

    fun unregisterConnectionEventListener(eventListener: ServerConnectionEventListener) {
        if (asynchronousEventListeners.isEmpty()) {
            Timber.v("[%s] There are no event listeners to remove", Build.MODEL)
            return
        }
        asynchronousEventListeners.remove(eventListener)
    }

    fun getConnectionEventListeners(): ArrayList<ServerConnectionEventListener> {
        val copy = ArrayList<ServerConnectionEventListener>(asynchronousEventListeners.size)
        copy.addAll(asynchronousEventListeners)
        return copy
    }

    @Synchronized
    fun setState(state: GattState) {
        Timber.v("[%s] Transitioning from state %s to state %s", Build.MODEL, this.state.name, state.name)
        this.state = state
    }

    fun resetStates() {
        setState(GattState.DISCONNECTED)
    }

    @VisibleForTesting
    fun setMockMode(mockMode: Boolean) {
        this.mockMode = mockMode
    }

    fun setIntraTransactionDelay(txDelay: Long) {
        val oldValue = intraTransactionDelay.getAndSet(txDelay)
        Timber.v("[%s] Changing intra-transaction delay from %dms, to %dms", Build.MODEL, oldValue, intraTransactionDelay.get())
    }

    fun getIntraTransactionDelay(): Long = intraTransactionDelay.get()

    fun runTx(transaction: GattTransaction, callback: GattTransactionCallback) {
        if (intraTransactionDelay.get() == 0L) {
            queueTransaction(transaction, callback)
        } else {
            val currentTimeMillis = System.currentTimeMillis()
            Timber.v("[%s] Posting tx to queue in %dms", Build.MODEL, intraTransactionDelay.get())
            getMainHandler().postDelayed({
                val queueTimeMillis = System.currentTimeMillis()
                val timeToQueue = queueTimeMillis - currentTimeMillis
                Timber.v("[%s] Queueing tx %dms after posting", Build.MODEL, timeToQueue)
                queueTransaction(transaction, callback)
            }, intraTransactionDelay.get())
        }
    }

    private fun queueTransaction(transaction: GattTransaction, callback: GattTransactionCallback) {
        serverQueue?.engine()?.queueTransaction {
            transaction.commit { result ->
                ConnectionEngine.deliverOnMainLooper(callbackLooper, mainHandler) {
                    callback.onTransactionComplete(result)
                }
            }
        }
    }

    fun isConnected(): Boolean {
        val current = getGattState()
        return current != GattState.DISCONNECTED &&
            current != GattState.DISCONNECTING &&
            current != GattState.BT_OFF
    }

    fun getServerTransactionQueueController(): TransactionQueueController? = serverQueue

    fun connect(device: FitbitBluetoothDevice) {
        if (mockMode) {
            connectedDevices.add(device)
            mockConnect()
            return
        }
        if (connectedDevices.contains(device)) {
            return
        }
        val success = server!!.connect(device.btDevice, true)
        if (success) {
            connectedDevices.add(device)
            setState(GattState.CONNECTING)
        } else {
            setState(GattState.FAILURE_CONNECTING)
        }
    }

    private fun mockConnect() {
        Timber.i("[%s] Mock connecting!!!!", Build.MODEL)
        setState(GattState.CONNECTING)
        mainHandler.postDelayed({
            FitbitGatt.getInstance().getServerCallback()!!
                .onConnectionStateChange(null, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }, 1499)
    }

    private fun mockDisconnect() {
        Timber.i("[%s] Mock disconnecting!!!", Build.MODEL)
        setState(GattState.DISCONNECTING)
        mainHandler.postDelayed({
            FitbitGatt.getInstance().getServerCallback()!!
                .onConnectionStateChange(null, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        }, 150)
    }

    fun isDeviceConnected(device: FitbitBluetoothDevice): Boolean = connectedDevices.contains(device)

    fun disconnect(device: FitbitBluetoothDevice) {
        if (mockMode) {
            connectedDevices.remove(device)
            mockDisconnect()
            return
        }
        if (!connectedDevices.contains(device)) {
            return
        }
        server!!.cancelConnection(device.btDevice)
        connectedDevices.remove(device)
        setState(GattState.DISCONNECTING)
    }

    protected fun closeGattServer() {
        Timber.v("Unregistering gatt server listeners")
        for (listener in asynchronousEventListeners) {
            unregisterConnectionEventListener(listener)
        }
        val localServer = getServer()
        if (localServer != null) {
            setState(GattState.CLOSING_GATT_SERVER)
            FitbitGatt.getInstance().getAdvertiser().stop()
            Timber.v("Clearing gatt server services")
            localServer.clearServices()
            Timber.v("Closing gatt server")
            localServer.close()
            setState(GattState.CLOSE_GATT_SERVER_SUCCESS)
            serverQueue?.clearQueue()
            serverQueue?.stop()
        }
    }

    @NonNull
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("Object.toString(): ")
        builder.append(super.toString())
        builder.append(" isConnected: ")
        builder.append(isConnected())
        builder.append(" state: ")
        val gattState = getGattState()
        builder.append(gattState)
        builder.append(" state type: ")
        builder.append(gattState.stateType)
        builder.append(" numConnEvtListeners: ")
        builder.append(getConnectionEventListeners().size)
        return builder.toString()
    }

    fun getMainHandler(): Handler = mainHandler

    override fun close() {
        finish()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Synchronized
    protected fun finish() {
        val queue = serverQueue
        if (queue != null && !queue.isQueueThreadStopped) {
            queue.stop()
        }
    }
}
