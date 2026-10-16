/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.tx.ReadGattClientPhyTransaction;
import com.fitbit.bluetooth.fbgatt.tx.RequestGattClientPhyChangeTransaction;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PhyBelowApi26Test {

    private GattConnection connection;

    @After
    public void after() {
        SdkInt.restore();
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
    }

    @Test
    public void api21And25CompleteAsNoOpWithoutCallingPhy() throws Exception {
        assertNoOp(21);
        assertNoOp(25);
    }

    @Test
    public void api26StillAttemptsThePhyCall() throws Exception {
        SdkInt.set(26);
        BluetoothGatt gatt = attachedGatt();
        invoke(new ReadGattClientPhyTransaction(connection, GattState.READ_CURRENT_PHY_SUCCESS), null);
        verify(gatt).readPhy();
        invoke(new RequestGattClientPhyChangeTransaction(connection, GattState.REQUEST_PHY_CHANGE_SUCCESS, 1, 1, 0), null);
        verify(gatt).setPreferredPhy(1, 1, 0);
    }

    private void assertNoOp(int sdk) throws Exception {
        SdkInt.set(sdk);
        BluetoothGatt gatt = attachedGatt();
        TransactionResult read = invoke(new ReadGattClientPhyTransaction(connection, GattState.READ_CURRENT_PHY_SUCCESS), new TransactionResult[1]);
        TransactionResult change = invoke(new RequestGattClientPhyChangeTransaction(
                connection, GattState.REQUEST_PHY_CHANGE_SUCCESS, 1, 1, 0), new TransactionResult[1]);
        assertNotNull(read);
        assertNotNull(change);
        assertEquals(TransactionResult.TransactionResultStatus.FAILURE, read.getResultStatus());
        assertEquals(TransactionResult.TransactionResultStatus.FAILURE, change.getResultStatus());
        assertEquals(GattState.IDLE, connection.getGattState());
        verify(gatt, never()).readPhy();
        verify(gatt, never()).setPreferredPhy(1, 1, 0);
    }

    private BluetoothGatt attachedGatt() throws Exception {
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
        Looper looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        FitbitGatt gattManager = FitbitGatt.getInstance();
        if (gattManager.getAppContext() == null) {
            gattManager.isStarted.set(false);
            gattManager.start(context);
        }
        BluetoothDevice remote = mock(BluetoothDevice.class);
        when(remote.getAddress()).thenReturn("02:00:00:00:00:62");
        when(remote.toString()).thenReturn("02:00:00:00:00:62");
        connection = new GattConnection(new FitbitBluetoothDevice("02:00:00:00:00:62", "phy", remote), looper);
        connection.setState(GattState.IDLE);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        Field field = GattConnection.class.getDeclaredField("gatt");
        field.setAccessible(true);
        field.set(connection, gatt);
        return gatt;
    }

    private TransactionResult invoke(Object transaction, TransactionResult[] slot) throws Exception {
        TransactionResult[] held = slot == null ? new TransactionResult[1] : slot;
        Method method = transaction.getClass().getDeclaredMethod("transaction", GattTransactionCallback.class);
        method.setAccessible(true);
        method.invoke(transaction, (GattTransactionCallback) result -> held[0] = result);
        return held[0];
    }
}
