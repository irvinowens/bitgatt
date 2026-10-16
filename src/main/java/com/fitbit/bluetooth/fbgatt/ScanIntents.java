/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The one place a scan {@link PendingIntent} is created. Stop has to use the same
 * request code and flags as start or the platform will not match the original intent.
 */
final class ScanIntents {
    static Intent lastIntent;
    static int lastFlags;
    static int lastRequestCode;
    static int calls;
    static String lastAction;
    static Class<?> lastTarget;

    private ScanIntents() {
    }

    static void reset() {
        lastIntent = null;
        lastFlags = -1;
        lastRequestCode = -1;
        calls = 0;
        lastAction = null;
        lastTarget = null;
    }

    static Intent explicitScanIntent(Context context) {
        Intent broadcastIntent = new Intent(context, HandleIntentBasedScanResult.class);
        broadcastIntent.setAction(PeripheralScanner.SCANNED_DEVICE_ACTION);
        broadcastIntent.setClass(context, HandleIntentBasedScanResult.class);
        lastAction = PeripheralScanner.SCANNED_DEVICE_ACTION;
        lastTarget = HandleIntentBasedScanResult.class;
        return broadcastIntent;
    }

    static PendingIntent getBroadcast(Context context, Intent intent) {
        lastRequestCode = PeripheralScanner.BACKGROUND_SCAN_REQUEST_CODE;
        lastFlags = PeripheralScanner.scanPendingIntentFlags();
        lastIntent = intent;
        calls++;
        return PendingIntent.getBroadcast(context, lastRequestCode, intent, lastFlags);
    }
}
