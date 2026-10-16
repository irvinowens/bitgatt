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
import android.content.Intent;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FitbitBluetoothDeviceScanFieldsTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void optInOffLeavesExtendedFieldsEmptyOnEveryPath() throws Exception {
        SdkInt.set(28);
        rig = new ScannerTestRig();
        rig.gatt.setExtendedScanEnabled(false);
        ScanResult result = result("AA:BB:CC:DD:EE:21", 1, 2, false, true, 3, 0, -50);
        FitbitBluetoothDevice individual = deliverIndividual(result);
        assertEmpty(individual);
        FitbitBluetoothDevice batch = deliverBatch(result("AA:BB:CC:DD:EE:22", 1, 2, false, true, 3, 0, -50));
        assertEmpty(batch);
        FitbitBluetoothDevice intent = deliverIntent(result("AA:BB:CC:DD:EE:23", 1, 2, false, true, 3, 0, -50));
        assertEmpty(intent);
    }

    @Test
    public void optInOnCopiesTheSixScanResultFieldsOnEveryPath() throws Exception {
        SdkInt.set(28);
        rig = new ScannerTestRig();
        rig.gatt.setExtendedScanEnabled(true);
        FitbitBluetoothDevice individual = deliverIndividual(result("AA:BB:CC:DD:EE:31", 1, 2, false, true, 4, 1, -40));
        assertFields(individual, 1, 2, false, true, 4, 1);
        FitbitBluetoothDevice batch = deliverBatch(result("AA:BB:CC:DD:EE:32", 2, 3, true, false, 5, 2, -41));
        assertFields(batch, 2, 3, true, false, 5, 2);
        FitbitBluetoothDevice intent = deliverIntent(result("AA:BB:CC:DD:EE:33", 1, 3, false, true, 6, 0, -42));
        assertFields(intent, 1, 3, false, true, 6, 0);
    }

    @Test
    public void sameMacWithDifferentPhyFieldsStayEqualByAddress() {
        SdkInt.set(28);
        BluetoothDevice bt = mock(BluetoothDevice.class);
        when(bt.getAddress()).thenReturn("AA:BB:CC:DD:EE:40");
        when(bt.getName()).thenReturn("same");
        FitbitBluetoothDevice first = new FitbitBluetoothDevice(bt);
        FitbitBluetoothDevice second = new FitbitBluetoothDevice(bt);
        first.copyExtendedScanFields(result("AA:BB:CC:DD:EE:40", 1, 2, false, true, 1, 0, -40), true);
        second.copyExtendedScanFields(result("AA:BB:CC:DD:EE:40", 2, 3, true, false, 2, 1, -40), true);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.getAddress().hashCode(), first.hashCode());
    }

    private FitbitBluetoothDevice deliverIndividual(ScanResult result) {
        List<FitbitBluetoothDevice> found = new ArrayList<>();
        rig.gatt.registerGattEventListener(listener(found));
        rig.scanner.populateMockScanResultIndividualValue(0, result);
        assertEquals(1, found.size());
        return found.get(0);
    }

    private FitbitBluetoothDevice deliverBatch(ScanResult result) {
        List<FitbitBluetoothDevice> found = new ArrayList<>();
        rig.gatt.registerGattEventListener(listener(found));
        rig.scanner.populateMockScanResultBatchValues(Collections.singletonList(result));
        assertEquals(1, found.size());
        return found.get(0);
    }

    private FitbitBluetoothDevice deliverIntent(ScanResult result) throws Exception {
        rig.gatt.isStarted.set(true);
        rig.scanner.setIsPendingIntentScanning(true);
        List<FitbitBluetoothDevice> found = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        rig.gatt.registerGattEventListener(new RecordingGattCallback() {
            @Override
            public void onBluetoothPeripheralDiscovered(GattConnection connection) {
                found.add(connection.getDevice());
                latch.countDown();
            }
        });
        Intent intent = mock(Intent.class);
        doReturn(new ArrayList<>(Collections.singletonList(result)))
                .when(intent).getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        IntentScanReceivers.listening().onReceive(rig.context, intent);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        return found.get(0);
    }

    private static RecordingGattCallback listener(List<FitbitBluetoothDevice> found) {
        return new RecordingGattCallback() {
            @Override
            public void onBluetoothPeripheralDiscovered(GattConnection connection) {
                found.add(connection.getDevice());
            }
        };
    }

    private static void assertEmpty(FitbitBluetoothDevice device) {
        assertNull(device.getPrimaryPhy());
        assertNull(device.getSecondaryPhy());
        assertNull(device.isLegacyAdvertisement());
        assertNull(device.isConnectableAdvertisement());
        assertNull(device.getAdvertisingSid());
        assertNull(device.getDataStatus());
    }

    private static void assertFields(FitbitBluetoothDevice device, int primary, int secondary,
                                      boolean legacy, boolean connectable, int sid, int dataStatus) {
        assertEquals(Integer.valueOf(primary), device.getPrimaryPhy());
        assertEquals(Integer.valueOf(secondary), device.getSecondaryPhy());
        assertEquals(Boolean.valueOf(legacy), device.isLegacyAdvertisement());
        assertEquals(Boolean.valueOf(connectable), device.isConnectableAdvertisement());
        assertEquals(Integer.valueOf(sid), device.getAdvertisingSid());
        assertEquals(Integer.valueOf(dataStatus), device.getDataStatus());
    }

    private static ScanResult result(String address, int primary, int secondary, boolean legacy,
                                      boolean connectable, int sid, int dataStatus, int rssi) {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(address);
        when(device.getName()).thenReturn(address);
        ScanResult result = mock(ScanResult.class);
        when(result.getDevice()).thenReturn(device);
        when(result.getRssi()).thenReturn(rssi);
        when(result.getPrimaryPhy()).thenReturn(primary);
        when(result.getSecondaryPhy()).thenReturn(secondary);
        when(result.isLegacy()).thenReturn(legacy);
        when(result.isConnectable()).thenReturn(connectable);
        when(result.getAdvertisingSid()).thenReturn(sid);
        when(result.getDataStatus()).thenReturn(dataStatus);
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
