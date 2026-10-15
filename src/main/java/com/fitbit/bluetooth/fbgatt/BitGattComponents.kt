/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import com.fitbit.bluetooth.fbgatt.engine.ConnectionEngine
import java.util.Locale

/**
 * Hand-built graph for the engine. Connections ask it for the single dispatcher they own.
 * There is no Hilt, Dagger, or Koin.
 */
object BitGattComponents {
    @JvmStatic
    fun connectionEngine(deviceName: String?): ConnectionEngine {
        val name = String.format(Locale.ENGLISH, "%s GATT Transaction Thread", deviceName)
        return ConnectionEngine(name)
    }

    @JvmStatic
    fun serverEngine(): ConnectionEngine = ConnectionEngine("GATT Server Transaction Thread")

    @JvmStatic
    fun namedEngine(name: String): ConnectionEngine = ConnectionEngine(name)
}
