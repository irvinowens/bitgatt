/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.btcopies.BluetoothGattCharacteristicCopy;
import com.fitbit.bluetooth.fbgatt.btcopies.BluetoothGattDescriptorCopy;
import com.fitbit.bluetooth.fbgatt.tx.NotifyGattServerCharacteristicTransaction;
import com.fitbit.bluetooth.fbgatt.tx.WriteGattCharacteristicTransaction;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Api33AttributeBytesTest {

    private GattConnection connection;
    private GattServerConnection server;

    @After
    public void after() {
        SdkInt.restore();
        if (connection != null) {
            connection.getClientTransactionQueueController().stop();
        }
        if (server != null) {
            server.getServerTransactionQueueController().stop();
        }
    }

    @Test
    public void byteArrayCallbacksFillTheCopyWhenGetValueIsNull() {
        FitbitGatt.getInstance();
        BluetoothDevice remote = mock(BluetoothDevice.class);
        when(remote.getAddress()).thenReturn("02:00:00:00:00:64");
        FitbitBluetoothDevice fitbit = new FitbitBluetoothDevice("02:00:00:00:00:64", "bytes", remote);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(gatt.getDevice()).thenReturn(remote);
        byte[] value = new byte[]{0x0A, 0x0B};
        GattClientListener listener = mock(GattClientListener.class);
        when(listener.getDevice()).thenReturn(fitbit);
        GattClientCallback callback = new GattClientCallback();
        callback.addListener(listener);

        BluetoothGattCharacteristic characteristic = characteristic();
        when(characteristic.getValue()).thenReturn(null);
        callback.onCharacteristicRead(gatt, characteristic, value, BluetoothGatt.GATT_SUCCESS);
        ArgumentCaptor<BluetoothGattCharacteristicCopy> read = ArgumentCaptor.forClass(BluetoothGattCharacteristicCopy.class);
        verify(listener).onCharacteristicRead(eq(gatt), read.capture(), eq(BluetoothGatt.GATT_SUCCESS));
        assertArrayEquals(value, read.getValue().getValue());
        value[0] = 0x0C;
        assertArrayEquals(new byte[]{0x0A, 0x0B}, read.getValue().getValue());

        byte[] changed = new byte[]{0x01};
        callback.onCharacteristicChanged(gatt, characteristic, changed);
        ArgumentCaptor<BluetoothGattCharacteristicCopy> notify = ArgumentCaptor.forClass(BluetoothGattCharacteristicCopy.class);
        verify(listener).onCharacteristicChanged(eq(gatt), notify.capture());
        assertArrayEquals(changed, notify.getValue().getValue());

        BluetoothGattDescriptor descriptor = mock(BluetoothGattDescriptor.class);
        when(descriptor.getUuid()).thenReturn(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        when(descriptor.getValue()).thenReturn(null);
        when(descriptor.getPermissions()).thenReturn(BluetoothGattDescriptor.PERMISSION_READ);
        byte[] descriptorValue = new byte[]{0x02, 0x00};
        callback.onDescriptorRead(gatt, descriptor, BluetoothGatt.GATT_SUCCESS, descriptorValue);
        ArgumentCaptor<BluetoothGattDescriptorCopy> descriptorCopy = ArgumentCaptor.forClass(BluetoothGattDescriptorCopy.class);
        verify(listener).onDescriptorRead(eq(gatt), descriptorCopy.capture(), eq(BluetoothGatt.GATT_SUCCESS));
        assertArrayEquals(descriptorValue, descriptorCopy.getValue().getValue());
    }

    @Test
    public void writeAndNotifyUseTheByteArrayOverloadAndKeepWriteTypeAndConfirm() throws Exception {
        SdkInt.set(33);
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
        when(remote.getAddress()).thenReturn("02:00:00:00:00:65");
        when(remote.toString()).thenReturn("02:00:00:00:00:65");
        FitbitBluetoothDevice device = new FitbitBluetoothDevice("02:00:00:00:00:65", "write", remote);
        connection = new GattConnection(device, looper);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        byte[] value = new byte[]{0x11, 0x22};
        BluetoothGattCharacteristic characteristic = characteristic();
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getWriteType()).thenReturn(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        when(gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
                .thenReturn(BluetoothGatt.GATT_SUCCESS);
        Field field = GattConnection.class.getDeclaredField("gatt");
        field.setAccessible(true);
        field.set(connection, gatt);
        WriteGattCharacteristicTransaction write = new WriteGattCharacteristicTransaction(
                connection, GattState.WRITE_CHARACTERISTIC_SUCCESS, characteristic);
        Method transaction = WriteGattCharacteristicTransaction.class.getDeclaredMethod("transaction", GattTransactionCallback.class);
        transaction.setAccessible(true);
        transaction.invoke(write, (GattTransactionCallback) result -> {
        });
        verify(gatt).writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        verify(gatt, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class));

        BluetoothGattServer bluetoothGattServer = mock(BluetoothGattServer.class);
        when(bluetoothGattServer.notifyCharacteristicChanged(remote, characteristic, false, value))
                .thenReturn(BluetoothGatt.GATT_SUCCESS);
        server = new GattServerConnection(bluetoothGattServer, looper);
        NotifyGattServerCharacteristicTransaction notify = new NotifyGattServerCharacteristicTransaction(
                server, device, GattState.NOTIFY_CHARACTERISTIC_SUCCESS, characteristic, false);
        Method notifyTransaction = NotifyGattServerCharacteristicTransaction.class.getDeclaredMethod(
                "transaction", GattTransactionCallback.class);
        notifyTransaction.setAccessible(true);
        notifyTransaction.invoke(notify, (GattTransactionCallback) result -> {
        });
        verify(bluetoothGattServer).notifyCharacteristicChanged(remote, characteristic, false, value);
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, characteristic.getWriteType());
    }

    private static BluetoothGattCharacteristic characteristic() {
        BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        when(characteristic.getUuid()).thenReturn(UUID.fromString("E69169D1-11A7-4545-B7FC-02A3ADFC3EAC"));
        when(characteristic.getPermissions()).thenReturn(BluetoothGattCharacteristic.PERMISSION_READ);
        when(characteristic.getProperties()).thenReturn(BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        when(characteristic.getDescriptors()).thenReturn(Collections.emptyList());
        return characteristic;
    }
}
