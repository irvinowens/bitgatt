/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScanPendingIntentFlagsTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void api21Through30UsesUpdateCurrentOnly() {
        assertHelper(21);
        assertHelper(30);
        assertSites(26);
        assertSites(30);
        assertStopUsesTheHelper(21);
    }

    @Test
    public void api31And37AreMutableAndNotImmutable() {
        assertHelper(31);
        assertHelper(37);
        assertSites(31);
        assertSites(37);
    }

    private void assertHelper(int sdk) {
        SdkInt.set(sdk);
        int flags = PeripheralScanner.scanPendingIntentFlags();
        if (sdk >= 31) {
            assertEquals(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE, flags);
        } else {
            assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, flags);
        }
        assertEquals(0, flags & PendingIntent.FLAG_IMMUTABLE);
        assertEquals(21436, PeripheralScanner.BACKGROUND_SCAN_REQUEST_CODE);
        SdkInt.restore();
    }

    private void assertStopUsesTheHelper(int sdk) {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        ScanIntents.reset();
        Intent callerIntent = new Intent(rig.context, HandleIntentBasedScanResult.class);
        callerIntent.setAction(PeripheralScanner.SCANNED_DEVICE_ACTION);
        rig.gatt.stopBackgroundScanWithRegularIntent(rig.context, callerIntent);
        assertEquals(1, ScanIntents.calls);
        assertEquals(21436, ScanIntents.lastRequestCode);
        assertEquals(PeripheralScanner.scanPendingIntentFlags(), ScanIntents.lastFlags);
        assertEquals(0, ScanIntents.lastFlags & PendingIntent.FLAG_IMMUTABLE);
        rig.close();
        rig = null;
    }

    private void assertSites(int sdk) {
        SdkInt.set(sdk);
        int flags = PeripheralScanner.scanPendingIntentFlags();
        rig = new ScannerTestRig();
        ScannerInterface platform = mock(ScannerInterface.class);
        when(platform.isBluetoothEnabled()).thenReturn(true);
        when(platform.startScan(anyList(), any(ScanSettings.class), nullable(PendingIntent.class))).thenReturn(0);
        rig.scanner.injectMockScanner(platform);
        BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:FF");
        when(rig.adapter.getRemoteDevice(any(String.class))).thenReturn(remote);

        ScanIntents.reset();
        rig.scanner.startPendingIntentBasedBackgroundScan(
                Collections.singletonList(new ScanFilter.Builder().setDeviceAddress("AA:BB:CC:DD:EE:FF").build()),
                rig.context);
        int startFlags = ScanIntents.lastFlags;
        assertEquals(1, ScanIntents.calls);
        assertEquals(21436, ScanIntents.lastRequestCode);
        assertEquals(flags, startFlags);
        assertEquals(0, startFlags & PendingIntent.FLAG_IMMUTABLE);
        assertEquals(PeripheralScanner.SCANNED_DEVICE_ACTION, ScanIntents.lastAction);
        assertEquals(HandleIntentBasedScanResult.class, ScanIntents.lastTarget);
        assertNotNull(ScanIntents.lastIntent);

        Intent callerIntent = new Intent(rig.context, HandleIntentBasedScanResult.class);
        callerIntent.setAction(PeripheralScanner.SCANNED_DEVICE_ACTION);
        rig.scanner.startBackgroundScan(Collections.singletonList("AA:BB:CC:DD:EE:FF"), callerIntent, rig.context);
        assertEquals(21436, ScanIntents.lastRequestCode);
        assertEquals(flags, ScanIntents.lastFlags);

        rig.gatt.stopBackgroundScanWithRegularIntent(rig.context, callerIntent);
        assertEquals(21436, ScanIntents.lastRequestCode);
        assertEquals(startFlags, ScanIntents.lastFlags);
        rig.close();
        rig = null;
    }
}
