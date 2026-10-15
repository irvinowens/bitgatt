/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.content.BroadcastReceiver;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

final class IntentScanReceivers {
    private IntentScanReceivers() {
    }

    static HandleIntentBasedScanResult listening() {
        HandleIntentBasedScanResult receiver = spy(new HandleIntentBasedScanResult());
        doReturn(mock(BroadcastReceiver.PendingResult.class)).when(receiver).goAsync();
        return receiver;
    }
}
