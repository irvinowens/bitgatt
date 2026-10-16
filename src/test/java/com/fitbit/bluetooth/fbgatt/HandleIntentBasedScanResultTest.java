/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Intent;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HandleIntentBasedScanResultTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void onReceiveGoesAsyncAndStartsOffTheReceiverThread() throws Exception {
        rig = new ScannerTestRig();
        rig.gatt.isStarted.set(false);
        HandleIntentBasedScanResult receiver = spy(new HandleIntentBasedScanResult());
        BroadcastReceiver.PendingResult pending = mock(BroadcastReceiver.PendingResult.class);
        doReturn(pending).when(receiver).goAsync();
        CountDownLatch onReceiveReturned = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Thread> workThread = new AtomicReference<>();
        AtomicBoolean finishedOnReceiveThread = new AtomicBoolean(false);
        when(rig.context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)).thenAnswer(invocation -> {
            assertTrue("start ran inside onReceive", onReceiveReturned.await(2, TimeUnit.SECONDS));
            workThread.set(Thread.currentThread());
            return rig.bluetoothManager;
        });
        doAnswer(invocation -> {
            finishedOnReceiveThread.set(onReceiveReturned.getCount() > 0);
            finished.countDown();
            return null;
        }).when(pending).finish();
        Intent intent = mock(Intent.class);
        Thread testThread = Thread.currentThread();
        receiver.onReceive(rig.context, intent);
        verify(receiver).goAsync();
        onReceiveReturned.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertNotEquals(testThread, workThread.get());
        assertFalse(finishedOnReceiveThread.get());
    }

    @Test
    public void rssiAtOrBelowTheFloorStillAddsTheDevice() throws Exception {
        rig = new ScannerTestRig();
        rig.gatt.isStarted.set(true);
        rig.scanner.setIsPendingIntentScanning(true);
        rig.scanner.addRssiFilter(-40);
        List<String> addresses = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        rig.gatt.registerGattEventListener(new RecordingGattCallback() {
            @Override
            public void onBluetoothPeripheralDiscovered(GattConnection connection) {
                addresses.add(connection.getDevice().getAddress());
                latch.countDown();
            }
        });
        Intent intent = mock(Intent.class);
        ArrayList<ScanResult> results = new ArrayList<>();
        results.add(result("AA:BB:CC:DD:EE:61", -80));
        results.add(result("AA:BB:CC:DD:EE:62", -40));
        doReturn(results).when(intent).getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        IntentScanReceivers.listening().onReceive(rig.context, intent);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, addresses.size());
    }

    @Test
    public void api33ReadsTheTypedParcelableList() throws Exception {
        SdkInt.set(33);
        rig = new ScannerTestRig();
        rig.gatt.isStarted.set(true);
        rig.scanner.setIsPendingIntentScanning(true);
        Intent intent = mock(Intent.class);
        ScanResult result = result("AA:BB:CC:DD:EE:63", -20);
        doReturn(new ArrayList<>(Collections.singletonList(result)))
                .when(intent).getParcelableArrayListExtra(eq(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT), eq(ScanResult.class));
        CountDownLatch latch = new CountDownLatch(1);
        rig.gatt.registerGattEventListener(new RecordingGattCallback() {
            @Override
            public void onBluetoothPeripheralDiscovered(GattConnection connection) {
                latch.countDown();
            }
        });
        IntentScanReceivers.listening().onReceive(rig.context, intent);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(intent).getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class);
    }

    @Test
    public void errorExtraAddsNoDeviceAndSurfacesScanFailed() throws Exception {
        rig = new ScannerTestRig();
        rig.gatt.isStarted.set(true);
        List<ScanFailure> failures = new ArrayList<>();
        List<GattConnection> found = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        rig.gatt.registerGattEventListener(new RecordingGattCallback() {
            @Override
            public void onBluetoothPeripheralDiscovered(GattConnection connection) {
                found.add(connection);
            }

            @Override
            public void onScanFailed(ScanFailure failure) {
                failures.add(failure);
                latch.countDown();
            }
        });
        Intent intent = mock(Intent.class);
        when(intent.getIntExtra(eq(BluetoothLeScanner.EXTRA_ERROR_CODE), anyInt()))
                .thenReturn(ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES.getFailReason());
        IntentScanReceivers.listening().onReceive(rig.context, intent);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList(ScanFailure.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES), failures);
        assertTrue(found.isEmpty());
    }

    private static ScanResult result(String address, int rssi) {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(address);
        when(device.getName()).thenReturn(address);
        ScanResult result = mock(ScanResult.class);
        when(result.getDevice()).thenReturn(device);
        when(result.getRssi()).thenReturn(rssi);
        return result;
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
