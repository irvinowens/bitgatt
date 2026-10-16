/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.engine.ConnectionEngine;
import com.fitbit.bluetooth.fbgatt.tx.mocks.MockNoOpTransaction;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionCallbackThreadTest {

    private static final String MOCK_ADDRESS = "02:00:00:00:00:11";
    private Thread mainLooperThread;
    private GattConnection connection;

    @Before
    public void before() {
        mainLooperThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable posted = ConnectionEngine.pollMain(Thread.currentThread(), 200);
                    if (posted != null) {
                        posted.run();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "bitgatt-main-looper");
        mainLooperThread.setDaemon(true);
        mainLooperThread.start();

        Looper looper = mock(Looper.class);
        when(looper.getThread()).thenReturn(mainLooperThread);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        when(context.registerReceiver(any(), any())).thenReturn(new Intent("bitgatt-test"));
        FitbitGatt gatt = FitbitGatt.getInstance();
        gatt.unregisterAllGattEventListeners();
        gatt.clearConnectionsMap();
        gatt.isStarted.set(false);
        gatt.start(context);

        BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        when(bluetoothDevice.getAddress()).thenReturn(MOCK_ADDRESS);
        FitbitBluetoothDevice device = new FitbitBluetoothDevice(MOCK_ADDRESS, "callback-thread", bluetoothDevice);
        connection = new GattConnection(device, looper);
        connection.setMockMode(true);
        connection.setState(GattState.IDLE);
        gatt.putConnectionIntoDevices(device, connection);
    }

    @After
    public void after() {
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
        FitbitGatt.getInstance().clearConnectionsMap();
        FitbitGatt.getInstance().isStarted.set(false);
        if (mainLooperThread != null) {
            mainLooperThread.interrupt();
        }
    }

    @Test
    public void successfulTransactionCompletesOnTheMainLooper() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        connection.runTx(new MockNoOpTransaction(connection, GattState.IDLE, 0), result -> {
            callbackThread.set(Thread.currentThread());
            assertEquals(TransactionResult.TransactionResultStatus.SUCCESS, result.getResultStatus());
            done.countDown();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        Thread queueThread = connection.getClientTransactionQueueController().getTransactionThread();
        assertNotNull(queueThread);
        assertEquals(mainLooperThread, callbackThread.get());
        assertNotEquals(queueThread, callbackThread.get());
    }
}
