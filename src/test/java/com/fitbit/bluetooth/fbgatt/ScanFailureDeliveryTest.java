/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.util.ScanFailedReason;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.bluetooth.le.ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScanFailureDeliveryTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void hardwareAndTooFrequentFailuresLeaveThePendingIntentScanStopped() {
        deliver(true, SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES, ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
        deliver(true, SCAN_FAILED_SCANNING_TOO_FREQUENTLY, ScanFailure.SCAN_FAILED_SCANNING_TOO_FREQUENTLY);
        deliver(false, SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES, ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
        deliver(false, SCAN_FAILED_SCANNING_TOO_FREQUENTLY, ScanFailure.SCAN_FAILED_SCANNING_TOO_FREQUENTLY);
    }

    @Test
    public void bothEnumsMapCodes5And6AndStayDistinct() {
        assertEquals(ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES, ScanFailure.getFailureForReason(5));
        assertEquals(ScanFailure.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, ScanFailure.getFailureForReason(6));
        assertEquals(ScanFailedReason.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES, ScanFailedReason.getReasonForCode(5));
        assertEquals(ScanFailedReason.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, ScanFailedReason.getReasonForCode(6));
        assertNotEquals(ScanFailure.class, ScanFailedReason.class);
        assertNotEquals(ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES.name(),
                ScanFailedReason.class.getName());
    }

    private void deliver(boolean pendingIntentStart, int code, ScanFailure expected) {
        SdkInt.set(31);
        rig = new ScannerTestRig();
        ScannerInterface platform = mock(ScannerInterface.class);
        when(platform.isBluetoothEnabled()).thenReturn(true);
        when(platform.startScan(anyList(), any(ScanSettings.class), nullable(android.app.PendingIntent.class))).thenReturn(code);
        rig.scanner.injectMockScanner(platform);
        android.bluetooth.BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:11");
        when(rig.adapter.getRemoteDevice(anyString())).thenReturn(remote);
        List<ScanFailure> failures = new ArrayList<>();
        rig.gatt.registerGattEventListener(new RecordingGattCallback() {
            @Override
            public void onScanFailed(ScanFailure failure) {
                failures.add(failure);
            }
        });
        if (pendingIntentStart) {
            rig.scanner.startPendingIntentBasedBackgroundScan(
                    Collections.singletonList(new ScanFilter.Builder().setDeviceAddress("AA:BB:CC:DD:EE:11").build()),
                    rig.context);
        } else {
            rig.scanner.startBackgroundScan(Collections.singletonList("AA:BB:CC:DD:EE:11"),
                    new android.content.Intent(rig.context, HandleIntentBasedScanResult.class), rig.context);
        }
        assertFalse(rig.scanner.isPendingIntentScanning());
        assertEquals(Collections.singletonList(expected), failures);
        rig.close();
        rig = null;
    }

    private static class RecordingGattCallback implements FitbitGatt.FitbitGattCallback {
        @Override public void onBluetoothPeripheralDiscovered(GattConnection connection) {}
        @Override public void onBluetoothPeripheralDisconnected(GattConnection connection) {}
        @Override public void onFitbitGattReady() {}
        @Override public void onFitbitGattStartFailed() {}
        @Override public void onScanStarted() {}
        @Override public void onScanStopped() {}
        @Override public void onPendingIntentScanStopped() {}
        @Override public void onPendingIntentScanStarted() {}
        @Override public void onBluetoothOff() {}
        @Override public void onBluetoothOn() {}
        @Override public void onBluetoothTurningOn() {}
        @Override public void onBluetoothTurningOff() {}
    }
}
