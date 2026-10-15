/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.advertise

import android.bluetooth.le.AdvertiseCallback

/**
 * Why an advertise start did not stay on the radio.
 */
enum class AdvertiseError {
    DATA_TOO_LARGE,
    TOO_MANY_ADVERTISERS,
    ALREADY_STARTED,
    INTERNAL_ERROR,
    FEATURE_UNSUPPORTED,
    missingPermission,
    bluetoothOff,
    serverNotReady;

    companion object {
        @JvmStatic
        fun fromPlatform(code: Int): AdvertiseError {
            return when (code) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> DATA_TOO_LARGE
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> TOO_MANY_ADVERTISERS
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> ALREADY_STARTED
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> FEATURE_UNSUPPORTED
                else -> INTERNAL_ERROR
            }
        }
    }
}
