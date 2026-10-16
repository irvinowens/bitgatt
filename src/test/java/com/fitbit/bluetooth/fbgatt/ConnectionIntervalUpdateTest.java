/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.tx.RequestGattConnectionIntervalTransaction;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectionIntervalUpdateTest {

    private GattConnection connection;

    @After
    public void after() {
        SdkInt.restore();
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
            FitbitGatt.getInstance().getConnectionMap().remove(connection.getDevice());
        }
    }

    @Test
    public void priorityBooleanCompletesBeforeConnectionUpdatedAndTheEventDoesNotEnqueue() throws Exception {
        SdkInt.set(30);
        Looper looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        FitbitGatt fitbitGatt = FitbitGatt.getInstance();
        fitbitGatt.unregisterAllGattEventListeners();
        fitbitGatt.clearConnectionsMap();
        fitbitGatt.isStarted.set(false);
        fitbitGatt.start(context);
        BluetoothDevice remote = mock(BluetoothDevice.class);
        when(remote.getAddress()).thenReturn("02:00:00:00:00:63");
        when(remote.toString()).thenReturn("02:00:00:00:00:63");
        FitbitBluetoothDevice device = new FitbitBluetoothDevice("02:00:00:00:00:63", "interval", remote);
        connection = new GattConnection(device, looper);
        connection.setState(GattState.IDLE);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(gatt.getDevice()).thenReturn(remote);
        when(gatt.requestConnectionPriority(anyInt())).thenReturn(true);
        Field field = GattConnection.class.getDeclaredField("gatt");
        field.setAccessible(true);
        field.set(connection, gatt);
        FitbitGatt.getInstance().putConnectionIntoDevices(device, connection);

        AtomicInteger completions = new AtomicInteger();
        TransactionResult[] held = new TransactionResult[1];
        RequestGattConnectionIntervalTransaction transaction = new RequestGattConnectionIntervalTransaction(
                connection, GattState.REQUEST_CONNECTION_INTERVAL_SUCCESS, RequestGattConnectionIntervalTransaction.Speed.HIGH);
        Method method = RequestGattConnectionIntervalTransaction.class.getDeclaredMethod("transaction", GattTransactionCallback.class);
        method.setAccessible(true);
        method.invoke(transaction, (GattTransactionCallback) result -> {
            completions.incrementAndGet();
            held[0] = result;
        });

        assertEquals(TransactionResult.TransactionResultStatus.SUCCESS, held[0].getResultStatus());
        assertEquals(GattState.IDLE, connection.getGattState());
        assertEquals(GattState.REQUEST_CONNECTION_INTERVAL_SUCCESS, held[0].getResultState());

        ConnectionEventListener listener = mock(ConnectionEventListener.class);
        connection.registerConnectionEventListener(listener);
        new GattClientCallback().onConnectionUpdated(gatt, 6, 0, 500, BluetoothGatt.GATT_SUCCESS);

        verify(listener).onConnectionUpdated(any(TransactionResult.class), org.mockito.ArgumentMatchers.eq(connection));
        assertEquals(1, completions.get());
        assertEquals(TransactionResult.TransactionResultStatus.SUCCESS, held[0].getResultStatus());
        assertSame(GattState.REQUEST_CONNECTION_INTERVAL_SUCCESS, held[0].getResultState());
        assertEquals(GattState.IDLE, connection.getGattState());
    }
}
