/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspend entry beside [GattConnection.runTx]. The callback overload stays.
 */
suspend fun GattConnection.runTx(transaction: GattTransaction): TransactionResult =
    suspendCancellableCoroutine { continuation ->
        runTx(transaction) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
