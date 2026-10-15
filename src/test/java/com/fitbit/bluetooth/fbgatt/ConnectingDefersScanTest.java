/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;

import java.util.Collections;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectingDefersScanTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void periodicAndHighPriorityWaitUntilConnectingEndsThenRunOnce() {
        rig = new ScannerTestRig();
        ScannerInterface platform = mock(ScannerInterface.class);
        when(platform.isBluetoothEnabled()).thenReturn(true);
        rig.scanner.injectMockScanner(platform);
        rig.scanner.setScanFilters(Collections.singletonList(new ScanFilter.Builder().setDeviceName("bitgatt").build()));
        GattConnection connection = new GattConnection(new FitbitBluetoothDevice(rig.device("AA:BB:CC:DD:EE:50")), rig.looper);
        connection.setState(GattState.CONNECTING);
        rig.gatt.getConnectionMap().put(connection.getDevice(), connection);

        assertFalse(rig.scanner.startPeriodicScan(rig.context));
        assertFalse(rig.scanner.startHighPriorityScan(rig.context));
        verify(platform, never()).startScan(anyList(), any(), any(ScanCallback.class));

        connection.setState(GattState.IDLE);
        Runnable deferredPeriodic = rig.delayed.remove(0);
        deferredPeriodic.run();
        verify(platform, times(1)).startScan(anyList(), any(), any(ScanCallback.class));

        Runnable deferredHighPriority = rig.delayed.remove(0);
        deferredHighPriority.run();
        verify(platform, times(2)).startScan(anyList(), any(), any(ScanCallback.class));
    }
}
