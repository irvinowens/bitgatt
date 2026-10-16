/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.descriptors.CharacteristicNamespaceDescriptor;
import com.fitbit.bluetooth.fbgatt.descriptors.CharacteristicNotificationDescriptor;
import com.fitbit.bluetooth.fbgatt.tx.AddGattServerServiceCharacteristicTransaction;
import com.fitbit.bluetooth.fbgatt.util.GattStatus;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.eq;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ServerAttributePolicyTest {

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID PRESENTATION = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb");

    private GattServerConnection server;

    @After
    public void after() {
        if (server != null) {
            server.getServerTransactionQueueController().stop();
        }
        FitbitGatt.getInstance().setGattServer(null);
    }

    @Test
    public void readOnlyCharacteristicGetsNeitherDescriptor() throws Exception {
        BluetoothGattCharacteristic characteristic = characteristic(BluetoothGattCharacteristic.PROPERTY_READ);
        addDescriptors(characteristic);
        verify(characteristic, never()).addDescriptor(any(BluetoothGattDescriptor.class));
    }

    @Test
    public void notifyAndIndicateReceiveOnlyTheClientConfigurationDescriptor() throws Exception {
        assertOnlyClientConfiguration(BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertOnlyClientConfiguration(BluetoothGattCharacteristic.PROPERTY_INDICATE);
    }

    @Test
    public void executeWriteWithNoListenerSendsGattError() {
        FitbitGatt.getInstance();
        Looper looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        BluetoothGattServer bluetoothGattServer = mock(BluetoothGattServer.class);
        server = new GattServerConnection(bluetoothGattServer, looper);
        FitbitGatt.getInstance().setGattServer(server);
        GattServerCallback callback = new GattServerCallback();
        BluetoothDevice device = mock(BluetoothDevice.class);
        callback.onExecuteWrite(device, 7, true);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(bluetoothGattServer).sendResponse(eq(device), eq(7), eq(GattStatus.GATT_ERROR.getCode()), eq(0), payload.capture());
        assertEquals(0, payload.getValue().length);
        assertEquals(0x85, GattStatus.GATT_ERROR.getCode());
    }

    private void assertOnlyClientConfiguration(int property) throws Exception {
        BluetoothGattCharacteristic characteristic = characteristic(property);
        addDescriptors(characteristic);
        ArgumentCaptor<BluetoothGattDescriptor> captor = ArgumentCaptor.forClass(BluetoothGattDescriptor.class);
        verify(characteristic).addDescriptor(captor.capture());
        assertTrue(captor.getValue() instanceof CharacteristicNotificationDescriptor);
        assertEquals(CCCD, notificationUuid());
        assertTrue(!(captor.getValue() instanceof CharacteristicNamespaceDescriptor));
        assertEquals(PRESENTATION, presentationUuid());
    }

    private void addDescriptors(BluetoothGattCharacteristic characteristic) throws Exception {
        if (server == null) {
            Looper looper = mock(Looper.class);
            Thread thread = mock(Thread.class);
            when(thread.getName()).thenReturn("bitgatt-test");
            when(looper.getThread()).thenReturn(thread);
            server = new GattServerConnection(mock(BluetoothGattServer.class), looper);
        }
        AddGattServerServiceCharacteristicTransaction transaction = new AddGattServerServiceCharacteristicTransaction(
                server, GattState.ADD_SERVICE_CHARACTERISTIC_SUCCESS, mock(BluetoothGattService.class), characteristic);
        Method method = AddGattServerServiceCharacteristicTransaction.class.getDeclaredMethod(
                "addCriticalDescriptorsIfNecessary", BluetoothGattCharacteristic.class);
        method.setAccessible(true);
        method.invoke(transaction, characteristic);
    }

    private static UUID notificationUuid() throws Exception {
        java.lang.reflect.Field field = CharacteristicNotificationDescriptor.class.getDeclaredField("notificationDescriptorTwentyNineOhTwo");
        field.setAccessible(true);
        return (UUID) field.get(null);
    }

    private static UUID presentationUuid() throws Exception {
        java.lang.reflect.Field field = CharacteristicNamespaceDescriptor.class.getDeclaredField("notificationDescriptorTwentyNineOhFour");
        field.setAccessible(true);
        return (UUID) field.get(null);
    }

    private static BluetoothGattCharacteristic characteristic(int properties) {
        BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        when(characteristic.getProperties()).thenReturn(properties);
        when(characteristic.getDescriptor(any(UUID.class))).thenReturn(null);
        return characteristic;
    }
}
