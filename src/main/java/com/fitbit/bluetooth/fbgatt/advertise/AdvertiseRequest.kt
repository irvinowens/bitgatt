/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.advertise

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings

/**
 * One advertise start. Defaults are connectable, legacy, balanced mode, medium power, and no timeout.
 * There is no periodic-advertising field.
 */
data class AdvertiseRequest @JvmOverloads constructor(
    val connectable: Boolean = true,
    val scannable: Boolean = false,
    val legacy: Boolean = true,
    val primaryPhy: Int = BluetoothDevice.PHY_LE_1M,
    val secondaryPhy: Int = BluetoothDevice.PHY_LE_1M,
    val interval: Int = 400,
    val txPowerLevel: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
    val advertiseData: AdvertiseData? = null,
    val scanResponse: AdvertiseData? = null
)
