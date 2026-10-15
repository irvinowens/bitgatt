/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared unit-test wiring for the scanner. Delayed callbacks are held so a 120s scan
 * timeout cannot re-enter start and a connecting-deferral can be released by hand.
 */
final class ScannerTestRig {
    final Context context;
    final Handler handler;
    final Looper looper;
    final FitbitGatt gatt;
    final PeripheralScanner scanner;
    final BluetoothManager bluetoothManager;
    final BluetoothAdapter adapter;
    final List<Runnable> delayed = new ArrayList<>();

    ScannerTestRig() {
        looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        when(context.registerReceiver(any(), any())).thenReturn(new Intent("bitgatt-test"));
        when(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        bluetoothManager = mock(BluetoothManager.class);
        adapter = mock(BluetoothAdapter.class);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(bluetoothManager.getAdapter()).thenReturn(adapter);
        handler = mock(Handler.class);
        Answer<Boolean> hold = invocation -> {
            delayed.add(invocation.getArgument(0));
            return true;
        };
        doAnswer(hold).when(handler).post(any(Runnable.class));
        doAnswer(hold).when(handler).postDelayed(any(Runnable.class), anyLong());
        when(handler.getLooper()).thenReturn(looper);
        gatt = FitbitGatt.getInstance();
        gatt.setExtendedScanEnabled(false);
        gatt.unregisterAllGattEventListeners();
        gatt.clearConnectionsMap();
        gatt.isStarted.set(false);
        gatt.start(context);
        when(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetoothManager);
        scanner = gatt.getPeripheralScanner();
        if (scanner != null) {
            scanner.setHandler(handler);
            scanner.setExtendedScanEnabled(false);
            scanner.setLeHighDataThroughputPhyEnabled(false);
            scanner.cancelPeriodicalScan(context);
            scanner.cancelHighPriorityScan(context);
            scanner.cancelPendingIntentBasedBackgroundScan();
        }
        org.mockito.Mockito.clearInvocations(context);
    }

    void deny(String permission) {
        when(context.checkPermission(eq(permission), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
    }

    void grant(String permission) {
        when(context.checkPermission(eq(permission), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
    }

    BluetoothDevice device(String address) {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(address);
        when(device.getName()).thenReturn(address);
        return device;
    }

    void close() {
        gatt.unregisterAllGattEventListeners();
        gatt.clearConnectionsMap();
        SdkInt.restore();
    }
}
