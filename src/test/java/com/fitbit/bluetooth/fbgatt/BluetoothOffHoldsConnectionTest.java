/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BluetoothOffHoldsConnectionTest {

    private static final String MOCK_ADDRESS = "02:00:00:00:00:12";
    private FitbitGatt gatt;
    private FitbitBluetoothDevice device;
    private GattConnection connection;
    private BluetoothGattServer server;

    @Before
    public void before() {
        Looper looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        when(context.registerReceiver(any(), any())).thenReturn(new Intent("bitgatt-test"));
        gatt = FitbitGatt.getInstance();
        gatt.unregisterAllGattEventListeners();
        gatt.clearConnectionsMap();
        gatt.isStarted.set(false);
        gatt.start(context);
        BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        when(bluetoothDevice.getAddress()).thenReturn(MOCK_ADDRESS);
        device = new FitbitBluetoothDevice(MOCK_ADDRESS, "held-connection", bluetoothDevice);
        connection = new GattConnection(device, looper);
        connection.setMockMode(true);
        connection.setState(GattState.IDLE);
        gatt.putConnectionIntoDevices(device, connection);
        server = mock(BluetoothGattServer.class);
        gatt.setGattServer(new GattServerConnection(server, looper));
    }

    @After
    public void after() {
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
        gatt.clearConnectionsMap();
        gatt.setGattServer(null);
        gatt.isStarted.set(false);
    }

    @Test
    public void bluetoothOffDropsQueuedWorkAndKeepsTheConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean(false);
        TransactionQueueController controller = connection.getClientTransactionQueueController();
        controller.queueTransaction(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        controller.queueTransaction(() -> queuedRan.set(true));

        gatt.bluetoothOff();

        assertEquals(GattState.BT_OFF, connection.getGattState());
        assertSame(connection, gatt.getConnection(device));
        assertFalse(queuedRan.get());
        verify(server, never()).close();
        assertFalse(controller.isQueueThreadStopped());

        release.countDown();
        Thread.sleep(150);
        assertFalse(queuedRan.get());
        assertFalse(controller.isQueueThreadStopped());

        gatt.bluetoothOn();
        assertEquals(GattState.DISCONNECTED, connection.getGattState());
        assertSame(connection, gatt.getConnection(device));
        verify(server, never()).close();
    }
}
