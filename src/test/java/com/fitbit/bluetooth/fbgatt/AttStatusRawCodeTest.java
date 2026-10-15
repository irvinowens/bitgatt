/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.btcopies.BluetoothGattCharacteristicCopy;
import com.fitbit.bluetooth.fbgatt.tx.WriteGattCharacteristicTransaction;
import com.fitbit.bluetooth.fbgatt.util.GattDisconnectReason;
import com.fitbit.bluetooth.fbgatt.util.GattStatus;
import com.fitbit.bluetooth.fbgatt.util.GattUtils;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AttStatusRawCodeTest {

    private GattConnection connection;

    @After
    public void after() {
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
    }

    @Test
    public void characteristicWriteStoresZeroAndRaw133() throws Exception {
        connection = connection();
        BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        when(characteristic.getUuid()).thenReturn(UUID.fromString("E69169D1-11A7-4545-B7FC-02A3ADFC3EAC"));
        when(characteristic.getPermissions()).thenReturn(BluetoothGattCharacteristic.PERMISSION_READ);
        when(characteristic.getProperties()).thenReturn(BluetoothGattCharacteristic.PROPERTY_WRITE);
        when(characteristic.getDescriptors()).thenReturn(Collections.emptyList());
        WriteGattCharacteristicTransaction transaction = new WriteGattCharacteristicTransaction(
                connection, GattState.WRITE_CHARACTERISTIC_SUCCESS, characteristic);
        TransactionResult success = completeWrite(transaction, characteristic, BluetoothGatt.GATT_SUCCESS);
        assertEquals(0, responseStatus(success));

        TransactionResult failure = completeWrite(transaction, characteristic, 133);
        assertEquals(133, responseStatus(failure));
        assertNotEquals(GattDisconnectReason.getReasonForCode(133).ordinal(), responseStatus(failure));
        failure.getResponseCodeString();
    }

    @Test
    public void theTwo0083NamesDoNotResolveToEachOther() {
        assertNotEquals(GattStatus.GATT_ILLEGAL_PARAMETER.getCode(), GattStatus.GATT_DB_FULL.getCode());
        assertEquals(GattStatus.GATT_ILLEGAL_PARAMETER, GattStatus.getStatusForCode(GattStatus.GATT_ILLEGAL_PARAMETER.getCode()));
        assertEquals(GattStatus.GATT_DB_FULL, GattStatus.getStatusForCode(GattStatus.GATT_DB_FULL.getCode()));
        assertNotEquals(GattStatus.GATT_ILLEGAL_PARAMETER, GattStatus.getStatusForCode(GattStatus.GATT_DB_FULL.getCode()));
        assertNotEquals(GattStatus.GATT_DB_FULL, GattStatus.getStatusForCode(GattStatus.GATT_ILLEGAL_PARAMETER.getCode()));
    }

    @Test
    public void notificationSentForwards133NotTheOrdinal() {
        FitbitGatt.getInstance();
        GattServerCallback callback = new GattServerCallback();
        GattServerListener listener = mock(GattServerListener.class);
        callback.addListener(listener);
        BluetoothDevice device = mock(BluetoothDevice.class);
        callback.onNotificationSent(device, 133);
        verify(listener).onServerNotificationSent(device, 133);
        assertNotEquals(133, GattStatus.getStatusForCode(133).ordinal());
    }

    private TransactionResult completeWrite(WriteGattCharacteristicTransaction transaction,
                                            BluetoothGattCharacteristic characteristic,
                                            int status) {
        final TransactionResult[] held = new TransactionResult[1];
        transaction.callback = result -> held[0] = result;
        BluetoothGattCharacteristicCopy copy = new GattUtils().copyCharacteristic(characteristic);
        transaction.onCharacteristicWrite(mock(BluetoothGatt.class), copy, status);
        return held[0];
    }

    private GattConnection connection() {
        Looper looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        FitbitGatt gatt = FitbitGatt.getInstance();
        gatt.unregisterAllGattEventListeners();
        gatt.clearConnectionsMap();
        gatt.isStarted.set(false);
        gatt.start(context);
        BluetoothDevice remote = mock(BluetoothDevice.class);
        when(remote.getAddress()).thenReturn("02:00:00:00:00:61");
        when(remote.toString()).thenReturn("02:00:00:00:00:61");
        return new GattConnection(new FitbitBluetoothDevice("02:00:00:00:00:61", "att", remote), looper);
    }

    private static int responseStatus(TransactionResult result) throws Exception {
        Field field = TransactionResult.class.getDeclaredField("responseStatus");
        field.setAccessible(true);
        return field.getInt(result);
    }
}
