/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.engine.Admit;
import com.fitbit.bluetooth.fbgatt.tx.SetClientConnectionStateTransaction;
import com.fitbit.bluetooth.fbgatt.tx.mocks.GattConnectMockTransaction;
import com.fitbit.bluetooth.fbgatt.tx.mocks.GattDisconnectMockTransaction;
import com.fitbit.bluetooth.fbgatt.tx.mocks.GattServerDisconnectMockTransaction;
import com.fitbit.bluetooth.fbgatt.tx.mocks.ReadGattCharacteristicMockTransaction;
import com.fitbit.bluetooth.fbgatt.tx.mocks.WriteGattCharacteristicMockTransaction;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class GattTransactionValidatorTest {

    private static final String MOCK_ADDRESS = "02:00:00:00:00:00";
    private GattConnection conn;
    private GattServerConnection serverConnection;
    private FitbitBluetoothDevice device;

    @Before
    public void before() {
        Handler mockHandler = mock(Handler.class);
        Looper mockLooper = mock(Looper.class);
        Thread mockThread = mock(Thread.class);
        when(mockThread.getName()).thenReturn("Irvin's mock thread");
        when(mockLooper.getThread()).thenReturn(mockThread);
        when(mockHandler.getLooper()).thenReturn(mockLooper);
        when(mockHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer((Answer) invocation -> {
            Message msg = invocation.getArgument(0);
            msg.getCallback().run();
            return null;
        });
        device = new FitbitBluetoothDevice(MOCK_ADDRESS, "fooDevice", mock(BluetoothDevice.class));
        conn = spy(new GattConnection(device, mockLooper));
        conn.setMockMode(true);
        when(conn.getMainHandler()).thenReturn(mockHandler);
        FitbitGatt.getInstance().putConnectionIntoDevices(device, conn);
        serverConnection = spy(new GattServerConnection(null, mockLooper));
        serverConnection.setMockMode(true);
        when(serverConnection.getMainHandler()).thenReturn(mockHandler);
        FitbitGatt.getInstance().setGattServer(serverConnection);
    }

    @Test
    public void testCanNotEnterTransactionWithBluetoothOff() {
        conn.resetStates();
        conn.setState(GattState.BT_OFF);
        GattTransaction tx = new GattConnectMockTransaction(conn, GattState.CONNECTED, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.INVALID_TARGET_STATE, guardState);
    }

    @Test
    public void testConnectionTransactionValidator() {
        conn.resetStates();
        GattTransaction tx = new GattConnectMockTransaction(conn, GattState.CONNECTED, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testConnectionWhileDisconnectedTransactionValidator() {
        conn.resetStates();
        GattTransaction tx = new GattConnectMockTransaction(conn, GattState.CONNECTED, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testDisconnectWhileConnectedTransactionValidator() {
        conn.setState(GattState.IDLE);
        GattTransaction tx = new GattDisconnectMockTransaction(conn, GattState.DISCONNECTED, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testConnectAfterDisconnectedTransactionValidator() {
        conn.setState(GattState.DISCONNECTED);
        GattTransaction tx = new GattConnectMockTransaction(conn, GattState.CONNECTED, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testAnythingOtherThanConnectWhileDisconnectedTransactionValidator() {
        conn.resetStates();
        GattTransaction tx = new ReadGattCharacteristicMockTransaction(conn,
                GattState.READ_CHARACTERISTIC_SUCCESS,
                new BluetoothGattCharacteristic(UUID.randomUUID(),
                        BluetoothGattCharacteristic.PERMISSION_READ,
                        BluetoothGattCharacteristic.PROPERTY_INDICATE),
                new byte[] { 0x12, 0x14},
                false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.INVALID_TARGET_STATE, guardState);
    }

    @Test
    public void testSetStateInErrorConditionValidatorTest(){
        conn.resetStates();
        conn.setState(GattState.WRITE_CHARACTERISTIC_FAILURE);
        GattTransaction tx = new SetClientConnectionStateTransaction(conn, GattState.GATT_CONNECTION_STATE_SET_SUCCESSFULLY, GattState.IDLE);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testServerDisconnectionTransactionValidator() {
        conn.resetStates();
        conn.setState(GattState.CONNECTED);
        GattTransaction tx = new GattServerDisconnectMockTransaction(serverConnection, GattState.DISCONNECTED, device, false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }

    @Test
    public void testSettingTransactionTimeout(){
        conn.resetStates();
        conn.setState(GattState.CONNECTED);
        WriteGattCharacteristicMockTransaction writeGattCharacteristicMockTransaction = new WriteGattCharacteristicMockTransaction(conn, GattState.WRITE_CHARACTERISTIC_SUCCESS, new BluetoothGattCharacteristic(UUID.randomUUID(),
                BluetoothGattCharacteristic.PERMISSION_READ,
                BluetoothGattCharacteristic.PROPERTY_INDICATE),
                new byte[] { 0x12, 0x14},
                false, 100L);
        Assert.assertEquals(100L, writeGattCharacteristicMockTransaction.getTimeout());
    }

    @Test
    public void stickyErrorRejectsTheNextOrdinaryTransaction() {
        conn.setState(GattState.WRITE_CHARACTERISTIC_FAILURE);
        GattTransaction tx = new ReadGattCharacteristicMockTransaction(conn,
                GattState.READ_CHARACTERISTIC_SUCCESS,
                new BluetoothGattCharacteristic(UUID.randomUUID(),
                        BluetoothGattCharacteristic.PERMISSION_READ,
                        BluetoothGattCharacteristic.PROPERTY_INDICATE),
                new byte[] { 0x12, 0x14},
                false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.INVALID_TARGET_STATE, guardState);
    }

    @Test
    public void timeoutLeavesInProgressAndTheNextTransactionIsAdmitted() {
        conn.setState(GattState.WRITING_CHARACTERISTIC);
        GattTransaction tx = new WriteGattCharacteristicMockTransaction(conn, GattState.WRITE_CHARACTERISTIC_SUCCESS, new BluetoothGattCharacteristic(UUID.randomUUID(),
                BluetoothGattCharacteristic.PERMISSION_READ,
                BluetoothGattCharacteristic.PROPERTY_INDICATE),
                new byte[] { 0x12, 0x14},
                false);
        Admit.GuardState guardState = Admit.check(conn.getGattState(), tx.getSuccessState(), tx.admitsRegardlessOfState());
        Assert.assertEquals(Admit.GuardState.OK, guardState);
    }
}
