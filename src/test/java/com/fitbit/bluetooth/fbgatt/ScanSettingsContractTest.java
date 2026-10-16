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
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScanSettingsContractTest {

    private ScannerTestRig rig;
    private ScannerInterface platform;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void defaultPeriodicAndPendingIntentSettingsAreLowPowerOnly() {
        SdkInt.set(31);
        open();
        rig.scanner.setScanFilters(Collections.singletonList(new ScanFilter.Builder().setDeviceName("bitgatt").build()));
        assertTrue(rig.scanner.startPeriodicScan(rig.context));
        ScanSettings periodic = callbackSettings();
        assertLowPowerOnly(periodic);

        SdkInt.set(26);
        android.bluetooth.BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:10");
        when(rig.adapter.getRemoteDevice(anyString())).thenReturn(remote);
        assertTrue(rig.scanner.startPendingIntentBasedBackgroundScan(
                Collections.singletonList(new ScanFilter.Builder().setDeviceAddress("AA:BB:CC:DD:EE:10").build()),
                rig.context));
        ScanSettings pending = pendingSettings();
        assertNotNull(pending);
        assertLowPowerOnly(pending);
    }

    @Test
    public void highPriorityIsLowLatencyOnly() {
        SdkInt.set(31);
        open();
        rig.scanner.setScanFilters(Collections.singletonList(new ScanFilter.Builder().setDeviceName("bitgatt").build()));
        assertTrue(rig.scanner.startHighPriorityScan(rig.context));
        ScanSettings settings = callbackSettings();
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.getScanMode());
        assertEquals(0L, settings.getReportDelayMillis());
        assertTrue(settings.getLegacy());
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, settings.getPhy());
    }

    @Test
    public void optInOnApi26Through36SetsLegacyAndLongRangePhyWithoutHdt() {
        assertExtended(26);
        assertExtended(36);
    }

    @Test
    public void optInOnApi37AddsHighDataThroughputPhyOnlyWhenAskedAndSupported() {
        ScanSettings asked = extended(37, true, BluetoothStatusCodes.FEATURE_SUPPORTED);
        assertFalse(asked.getLegacy());
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_CODED_MASK | BluetoothDevice.PHY_LE_HDT_MASK,
                asked.getPhy());

        ScanSettings notAsked = extended(37, false, BluetoothStatusCodes.FEATURE_SUPPORTED);
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_CODED_MASK, notAsked.getPhy());
        assertEquals(0, notAsked.getPhy() & BluetoothDevice.PHY_LE_HDT_MASK);

        ScanSettings unsupported = extended(37, true, BluetoothStatusCodes.FEATURE_NOT_SUPPORTED);
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_CODED_MASK, unsupported.getPhy());
    }

    private void assertExtended(int sdk) {
        ScanSettings settings = extended(sdk, true, BluetoothStatusCodes.FEATURE_SUPPORTED);
        assertFalse(settings.getLegacy());
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_CODED_MASK, settings.getPhy());
        assertEquals(0, settings.getPhy() & BluetoothDevice.PHY_LE_HDT_MASK);
    }

    private ScanSettings extended(int sdk, boolean askForHdt, int support) {
        SdkInt.set(sdk);
        open();
        when(rig.adapter.isLeHighDataThroughputPhySupported()).thenReturn(support);
        rig.scanner.setExtendedScanEnabled(true);
        rig.scanner.setLeHighDataThroughputPhyEnabled(askForHdt);
        rig.scanner.setScanFilters(Collections.singletonList(new ScanFilter.Builder().setDeviceName("bitgatt").build()));
        assertTrue(rig.scanner.startPeriodicScan(rig.context));
        ScanSettings settings = callbackSettings();
        rig.close();
        rig = null;
        return settings;
    }

    private void open() {
        rig = new ScannerTestRig();
        platform = mock(ScannerInterface.class);
        when(platform.isBluetoothEnabled()).thenReturn(true);
        rig.scanner.injectMockScanner(platform);
    }

    private void assertLowPowerOnly(ScanSettings settings) {
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, settings.getScanMode());
        assertEquals(0L, settings.getReportDelayMillis());
        assertTrue(settings.getLegacy());
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, settings.getPhy());
    }

    private ScanSettings callbackSettings() {
        ArgumentCaptor<ScanSettings> settings = ArgumentCaptor.forClass(ScanSettings.class);
        verify(platform).startScan(anyList(), settings.capture(), any(ScanCallback.class));
        return settings.getValue();
    }

    private ScanSettings pendingSettings() {
        ArgumentCaptor<ScanSettings> settings = ArgumentCaptor.forClass(ScanSettings.class);
        verify(platform).startScan(anyList(), settings.capture(), nullable(PendingIntent.class));
        return settings.getValue();
    }
}
