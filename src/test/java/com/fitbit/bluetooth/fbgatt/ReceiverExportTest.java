/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.tx.CreateBondTransaction;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ReceiverExportTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            new LowEnergyAclListener().unregister(rig.context);
            rig.close();
        }
    }

    @Test
    public void api33And37RegisterSystemReceiversExported() throws Exception {
        assertExported(33);
        assertExported(37);
    }

    @Test
    public void api21And30UseTheTwoArgumentRegisterReceiver() throws Exception {
        assertLegacy(21);
        assertLegacy(30);
    }

    private void assertExported(int sdk) throws Exception {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        LowEnergyAclListener acl = new LowEnergyAclListener();
        acl.unregister(rig.context);
        acl.register(rig.context);
        ArgumentCaptor<IntentFilter> filters = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, times(4)).registerReceiver(any(), filters.capture(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(ContextCompat.RECEIVER_EXPORTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_CONNECTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_DISCONNECTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_FOUND));
        acl.unregister(rig.context);

        BluetoothRadioStatusListener radio = new BluetoothRadioStatusListener(rig.context, false, rig.looper);
        radio.startListening();
        ArgumentCaptor<IntentFilter> radioFilter = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, org.mockito.Mockito.atLeastOnce()).registerReceiver(any(), radioFilter.capture(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(ContextCompat.RECEIVER_EXPORTED));
        assertEquals(BluetoothAdapter.ACTION_STATE_CHANGED, radioFilter.getValue().getAction(0));

        registerBond(rig);
        ArgumentCaptor<IntentFilter> bondFilter = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, org.mockito.Mockito.atLeastOnce()).registerReceiver(any(), bondFilter.capture(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(ContextCompat.RECEIVER_EXPORTED));
        boolean bond = false;
        for (IntentFilter filter : bondFilter.getAllValues()) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(filter.getAction(0))) {
                bond = true;
            }
        }
        assertTrue(bond);
        rig.close();
        rig = null;
    }

    private void assertLegacy(int sdk) throws Exception {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        LowEnergyAclListener acl = new LowEnergyAclListener();
        acl.unregister(rig.context);
        acl.register(rig.context);
        ArgumentCaptor<IntentFilter> filters = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, times(4)).registerReceiver(any(), filters.capture());
        verify(rig.context, never()).registerReceiver(any(), any(), any(), any(), anyInt());
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_CONNECTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_DISCONNECTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));
        assertTrue(hasAction(filters, BluetoothDevice.ACTION_FOUND));
        acl.unregister(rig.context);

        BluetoothRadioStatusListener radio = new BluetoothRadioStatusListener(rig.context, false, rig.looper);
        radio.startListening();
        ArgumentCaptor<IntentFilter> radioFilter = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, times(5)).registerReceiver(any(), radioFilter.capture());
        assertEquals(BluetoothAdapter.ACTION_STATE_CHANGED, radioFilter.getValue().getAction(0));

        registerBond(rig);
        ArgumentCaptor<IntentFilter> bondFilter = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, times(6)).registerReceiver(any(), bondFilter.capture());
        assertEquals(BluetoothDevice.ACTION_BOND_STATE_CHANGED, bondFilter.getValue().getAction(0));
        verify(rig.context, never()).registerReceiver(any(), any(), any(), any(), anyInt());
        rig.close();
        rig = null;
    }

    private static void registerBond(ScannerTestRig rig) throws Exception {
        BluetoothDevice bt = rig.device("AA:BB:CC:DD:EE:09");
        GattConnection connection = new GattConnection(new FitbitBluetoothDevice(bt), rig.looper);
        CreateBondTransaction transaction = new CreateBondTransaction(connection, GattState.CREATE_BOND_SUCCESS);
        Method bond = CreateBondTransaction.class.getDeclaredMethod("createBond", Context.class, FitbitBluetoothDevice.class);
        bond.setAccessible(true);
        bond.invoke(transaction, rig.context, connection.getDevice());
    }

    private static boolean hasAction(ArgumentCaptor<IntentFilter> filters, String action) {
        for (IntentFilter filter : filters.getAllValues()) {
            if (action.equals(filter.getAction(0))) {
                return true;
            }
        }
        return false;
    }
}
