/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.advertise

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.GattServerConnection
import com.fitbit.bluetooth.fbgatt.GattState
import com.fitbit.bluetooth.fbgatt.util.GattUtils
import java.util.UUID

/**
 * One active advertiser. Connectable starts wait until the GATT server is idle and hosting
 * services. Non-connectable starts do not open a server. Bluetooth turning on does not resume
 * advertising. A failure here does not touch the connection engine.
 */
class BitGattAdvertiser internal constructor(
    private val appContext: () -> Context?,
    private val bluetoothOn: () -> Boolean,
    private val server: () -> GattServerConnection?,
    private val adapterFor: (Context) -> BluetoothAdapter?,
    private val listeners: () -> Iterable<FitbitGatt.FitbitGattCallback>
) {
    private var active = false
    private var legacyCallback: AdvertiseCallback? = null
    private var setCallback: AdvertisingSetCallback? = null

    /**
     * @return null when the platform start was issued. A non-null value was refused before the radio.
     */
    fun start(request: AdvertiseRequest = AdvertiseRequest()): AdvertiseError? {
        val context = appContext()
        if (context == null || !bluetoothOn()) {
            return AdvertiseError.bluetoothOff
        }
        if (GattUtils.missingAdvertisePermission(context) != null) {
            return AdvertiseError.missingPermission
        }
        if (active) {
            return AdvertiseError.ALREADY_STARTED
        }
        if (request.connectable && !serverReady(request)) {
            return AdvertiseError.serverNotReady
        }
        val adapter = adapterFor(context) ?: return AdvertiseError.bluetoothOff
        val advertiser = adapter.bluetoothLeAdvertiser ?: return AdvertiseError.FEATURE_UNSUPPORTED
        val data = request.advertiseData ?: dataForHostedServices()
        val extended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported
        if (extended) {
            startSet(advertiser, request, data)
        } else {
            startLegacy(advertiser, request, data)
        }
        active = true
        return null
    }

    fun stop() {
        val context = appContext()
        val advertiser = if (context == null) null else adapterFor(context)?.bluetoothLeAdvertiser
        val callback = legacyCallback
        val set = setCallback
        val wasActive = active
        active = false
        legacyCallback = null
        setCallback = null
        if (callback != null) {
            advertiser?.stopAdvertising(callback)
        }
        if (set != null) {
            advertiser?.stopAdvertisingSet(set)
        }
        if (wasActive) {
            dispatch(false, null)
        }
    }

    fun isAdvertising(): Boolean = active

    private fun serverReady(request: AdvertiseRequest): Boolean {
        val connection = server() ?: return false
        if (connection.getGattState() != GattState.IDLE) {
            return false
        }
        val hosted = hostedServiceUuids()
        if (hosted.isEmpty()) {
            return false
        }
        val requested = request.advertiseData?.serviceUuids ?: return true
        return hosted.containsAll(requested.mapNotNull { it.uuid })
    }

    private fun hostedServiceUuids(): List<UUID> {
        val services = server()?.getServer()?.services ?: return emptyList()
        return services.mapNotNull { it?.uuid }
    }

    private fun dataForHostedServices(): AdvertiseData {
        val builder = AdvertiseData.Builder()
        for (uuid in hostedServiceUuids()) {
            builder.addServiceUuid(ParcelUuid(uuid))
        }
        return builder.build()
    }

    private fun startLegacy(advertiser: BluetoothLeAdvertiser, request: AdvertiseRequest, data: AdvertiseData) {
        val settings = AdvertiseSettings.Builder()
            .setConnectable(request.connectable)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(request.txPowerLevel)
            .setTimeout(0)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                dispatch(true, null)
            }

            override fun onStartFailure(errorCode: Int) {
                active = false
                legacyCallback = null
                dispatch(false, AdvertiseError.fromPlatform(errorCode))
            }
        }
        legacyCallback = callback
        advertiser.startAdvertising(settings, data, request.scanResponse, callback)
    }

    private fun startSet(advertiser: BluetoothLeAdvertiser, request: AdvertiseRequest, data: AdvertiseData) {
        val parameters = AdvertisingSetParameters.Builder()
            .setConnectable(request.connectable)
            .setScannable(request.scannable)
            .setLegacyMode(request.legacy)
            .setPrimaryPhy(request.primaryPhy)
            .setSecondaryPhy(request.secondaryPhy)
            .setInterval(request.interval)
            .setTxPowerLevel(request.txPowerLevel)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    dispatch(true, null)
                } else {
                    active = false
                    setCallback = null
                    dispatch(false, AdvertiseError.fromPlatform(status))
                }
            }
        }
        setCallback = callback
        advertiser.startAdvertisingSet(parameters, data, request.scanResponse, null, null, callback)
    }

    private fun dispatch(advertising: Boolean, error: AdvertiseError?) {
        for (listener in listeners()) {
            listener.onAdvertiseStateChanged(advertising, error)
        }
    }
}
