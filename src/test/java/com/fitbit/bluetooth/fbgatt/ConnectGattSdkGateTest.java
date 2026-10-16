/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattConnectionSettings;
import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectGattSdkGateTest {

    private ScannerTestRig rig;
    private GattConnection connection;

    @After
    public void after() {
        if (connection != null) {
            connection.setState(GattState.IDLE);
            connection.getClientTransactionQueueController().stop();
        }
        if (rig != null) {
            if (rig.scanner != null) {
                rig.scanner.setDeferScanWhileConnecting(false);
            }
            rig.close();
        } else {
            SdkInt.restore();
        }
    }

    @Test
    public void eachSdkBandUsesDirectConnectOnItsOverload() throws Exception {
        assertLegacy(21);
        assertLegacy(22);
        assertTransport(23);
        assertTransport(25);
        assertPhy(26);
        assertPhy(36);
        assertSettings(37);
    }

    private void assertLegacy(int sdk) {
        BluetoothDevice remote = fresh(sdk);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(remote.connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class))).thenReturn(gatt);
        assertTrue(connection.connect());
        verify(remote).connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class));
        verify(remote, never()).connectGatt(any(Context.class), anyBoolean(), any(BluetoothGattCallback.class), anyInt());
        verify(remote, never()).connectGatt(any(BluetoothGattConnectionSettings.class), any(Executor.class), any(BluetoothGattCallback.class));
    }

    private void assertTransport(int sdk) {
        BluetoothDevice remote = fresh(sdk);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(remote.connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class), eq(BluetoothDevice.TRANSPORT_LE)))
                .thenReturn(gatt);
        assertTrue(connection.connect());
        verify(remote).connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class), eq(BluetoothDevice.TRANSPORT_LE));
        verify(remote, never()).connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class));
        verify(remote, never()).connectGatt(any(Context.class), anyBoolean(), any(BluetoothGattCallback.class), anyInt(), anyInt());
    }

    private void assertPhy(int sdk) {
        BluetoothDevice remote = fresh(sdk);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(remote.connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class),
                eq(BluetoothDevice.TRANSPORT_LE), eq(BluetoothDevice.PHY_LE_1M_MASK))).thenReturn(gatt);
        assertTrue(connection.connect());
        verify(remote).connectGatt(any(Context.class), eq(false), any(BluetoothGattCallback.class),
                eq(BluetoothDevice.TRANSPORT_LE), eq(BluetoothDevice.PHY_LE_1M_MASK));
        verify(remote, never()).connectGatt(any(BluetoothGattConnectionSettings.class), any(Executor.class), any(BluetoothGattCallback.class));
    }

    private void assertSettings(int sdk) throws Exception {
        BluetoothDevice remote = fresh(sdk);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(remote.connectGatt(any(BluetoothGattConnectionSettings.class), any(Executor.class), any(BluetoothGattCallback.class)))
                .thenReturn(gatt);
        assertTrue(connection.connect());
        ArgumentCaptor<BluetoothGattConnectionSettings> settings = ArgumentCaptor.forClass(BluetoothGattConnectionSettings.class);
        ArgumentCaptor<Executor> executor = ArgumentCaptor.forClass(Executor.class);
        verify(remote).connectGatt(settings.capture(), executor.capture(), any(BluetoothGattCallback.class));
        assertFalse(settings.getValue().isAutoConnectEnabled());
        assertEquals(BluetoothDevice.TRANSPORT_LE, settings.getValue().getTransport());
        verify(remote, never()).connectGatt(any(Context.class), anyBoolean(), any(BluetoothGattCallback.class), anyInt(), anyInt());
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        executor.getValue().execute(() -> {
            threadName.set(Thread.currentThread().getName());
            ran.countDown();
        });
        assertTrue(ran.await(2, TimeUnit.SECONDS));
        assertEquals("BitGatt connectGatt", threadName.get());
    }

    private BluetoothDevice fresh(int sdk) {
        if (connection != null) {
            connection.setState(GattState.IDLE);
            connection.getClientTransactionQueueController().stop();
        }
        if (rig != null) {
            rig.close();
        }
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:80");
        connection = new GattConnection(new FitbitBluetoothDevice(remote.getAddress(), "connect", remote), rig.looper);
        return remote;
    }
}
