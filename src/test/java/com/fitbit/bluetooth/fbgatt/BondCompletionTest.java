/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.tx.CreateBondTransaction;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.core.content.ContextCompat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BondCompletionTest {

    private ScannerTestRig rig;
    private GattConnection first;
    private GattConnection second;

    @After
    public void after() {
        if (first != null) {
            first.getClientTransactionQueueController().stop();
        }
        if (second != null) {
            second.getClientTransactionQueueController().stop();
        }
        if (rig != null) {
            rig.close();
        } else {
            SdkInt.restore();
        }
    }

    @Test
    public void api23UsesTransportLeAndBelow23UsesTheNoArgCall() throws Exception {
        assertCreateBond(23, true);
        assertCreateBond(21, false);
    }

    @Test
    public void bondNoneFailsImmediatelyAndAnotherDevicesBondDoesNotCompleteThisOne() throws Exception {
        rig = start(23);
        BondRun ours = startBond(rig, "AA:BB:CC:DD:EE:71", 1);
        BondRun other = startBond(rig, "AA:BB:CC:DD:EE:72", 2);
        first = ours.connection;
        second = other.connection;
        Intent bonded = bondIntent(other.remote, BluetoothDevice.BOND_BONDED);
        other.receiver.onReceive(rig.context, bonded);
        assertTrue(other.done.await(2, TimeUnit.SECONDS));
        assertEquals(TransactionResult.TransactionResultStatus.SUCCESS, other.result.get().getResultStatus());
        Thread.sleep(200);
        assertNull(ours.result.get());
        assertEquals(GattState.CREATING_BOND, ours.connection.getGattState());

        ours.receiver.onReceive(rig.context, bondIntent(ours.remote, BluetoothDevice.BOND_NONE));
        assertTrue(ours.done.await(2, TimeUnit.SECONDS));
        assertEquals(TransactionResult.TransactionResultStatus.FAILURE, ours.result.get().getResultStatus());
        assertEquals(GattState.CREATE_BOND_FAILURE, ours.result.get().getResultState());
        assertEquals(GattState.IDLE, ours.connection.getGattState());
        ours.worker.join(1000);
        other.worker.interrupt();
        other.worker.join(1000);
    }

    @Test
    public void api36RegistersKeyMissingAndEncryptionChange() throws Exception {
        rig = start(36);
        BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:73");
        FitbitBluetoothDevice device = new FitbitBluetoothDevice(remote.getAddress(), "bond", remote);
        GattConnection connection = new GattConnection(device, rig.looper);
        first = connection;
        CreateBondTransaction transaction = new CreateBondTransaction(connection, GattState.CREATE_BOND_SUCCESS);
        Method bond = CreateBondTransaction.class.getDeclaredMethod("createBond", Context.class, FitbitBluetoothDevice.class);
        bond.setAccessible(true);
        bond.invoke(transaction, rig.context, device);
        ArgumentCaptor<IntentFilter> filters = ArgumentCaptor.forClass(IntentFilter.class);
        verify(rig.context, timeout(2000).atLeastOnce()).registerReceiver(
                any(BroadcastReceiver.class), filters.capture(), isNull(), isNull(), eq(ContextCompat.RECEIVER_EXPORTED));
        IntentFilter filter = filters.getValue();
        assertEquals(BluetoothDevice.ACTION_BOND_STATE_CHANGED, filter.getAction(0));
        assertEquals(BluetoothDevice.ACTION_KEY_MISSING, filter.getAction(1));
        assertEquals(BluetoothDevice.ACTION_ENCRYPTION_CHANGE, filter.getAction(2));
    }

    @Test
    public void repairingContextOnApi37IsNotAFailureAndRemoveBondIsNotCalled() throws Exception {
        rig = start(37);
        BondRun run = startBond(rig, "AA:BB:CC:DD:EE:74", 1);
        first = run.connection;
        Intent repairing = bondIntent(run.remote, BluetoothDevice.BOND_NONE);
        when(repairing.getIntExtra(eq(BluetoothDevice.EXTRA_PAIRING_CONTEXT), anyInt()))
                .thenReturn(BluetoothDevice.PAIRING_CONTEXT_REPAIRING);
        run.receiver.onReceive(rig.context, repairing);
        Thread.sleep(300);
        assertNull(run.result.get());
        assertEquals(GattState.CREATING_BOND, run.connection.getGattState());
        verify(rig.context, org.mockito.Mockito.never()).getSystemService(eq(Context.COMPANION_DEVICE_SERVICE));
        verify(run.remote).createBond(BluetoothDevice.TRANSPORT_LE);
        run.worker.interrupt();
        run.worker.join(1000);
    }

    private void assertCreateBond(int sdk, boolean transportLe) throws Exception {
        if (rig != null) {
            rig.close();
        }
        if (first != null) {
            first.getClientTransactionQueueController().stop();
        }
        rig = start(sdk);
        BluetoothDevice remote = rig.device("AA:BB:CC:DD:EE:70");
        FitbitBluetoothDevice device = new FitbitBluetoothDevice(remote.getAddress(), "bond", remote);
        first = new GattConnection(device, rig.looper);
        CreateBondTransaction transaction = new CreateBondTransaction(first, GattState.CREATE_BOND_SUCCESS);
        Method bond = CreateBondTransaction.class.getDeclaredMethod("createBond", Context.class, FitbitBluetoothDevice.class);
        bond.setAccessible(true);
        bond.invoke(transaction, rig.context, device);
        if (transportLe) {
            verify(remote).createBond(BluetoothDevice.TRANSPORT_LE);
        } else {
            verify(remote).createBond();
        }
        verify(rig.context, org.mockito.Mockito.never()).getSystemService(eq(Context.COMPANION_DEVICE_SERVICE));
    }

    private BondRun startBond(ScannerTestRig rig, String address, int registration) throws Exception {
        BluetoothDevice remote = rig.device(address);
        FitbitBluetoothDevice device = new FitbitBluetoothDevice(remote.getAddress(), "bond", remote);
        GattConnection connection = new GattConnection(device, rig.looper);
        connection.setState(GattState.IDLE);
        CreateBondTransaction transaction = new CreateBondTransaction(connection, GattState.CREATE_BOND_SUCCESS);
        AtomicReference<TransactionResult> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                Method method = CreateBondTransaction.class.getDeclaredMethod("transaction", GattTransactionCallback.class);
                method.setAccessible(true);
                method.invoke(transaction, (GattTransactionCallback) value -> {
                    result.set(value);
                    done.countDown();
                });
            } catch (Exception failure) {
                failure.printStackTrace();
            }
        }, "bond-" + address);
        worker.start();
        BondRun run = new BondRun();
        run.remote = remote;
        run.connection = connection;
        run.result = result;
        run.done = done;
        run.worker = worker;
        run.receiver = awaitReceiver(registration);
        return run;
    }

    private BroadcastReceiver awaitReceiver(int registration) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        org.mockito.exceptions.base.MockitoAssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<BroadcastReceiver> receivers = ArgumentCaptor.forClass(BroadcastReceiver.class);
            try {
                if (SdkInt.get() >= 33) {
                    verify(rig.context, atLeast(registration)).registerReceiver(
                            receivers.capture(), any(IntentFilter.class), isNull(), isNull(), anyInt());
                } else {
                    verify(rig.context, atLeast(registration)).registerReceiver(
                            receivers.capture(), any(IntentFilter.class));
                }
                return receivers.getAllValues().get(receivers.getAllValues().size() - 1);
            } catch (org.mockito.exceptions.base.MockitoAssertionError notYet) {
                last = notYet;
                Thread.sleep(20);
            }
        }
        throw new AssertionError(last);
    }

    private static Intent bondIntent(BluetoothDevice remote, int state) {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        doReturn(remote).when(intent).getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        when(intent.getIntExtra(eq(BluetoothDevice.EXTRA_BOND_STATE), anyInt())).thenReturn(state);
        when(intent.getIntExtra(eq(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE), anyInt())).thenReturn(BluetoothDevice.BOND_BONDING);
        when(intent.getIntExtra(eq(BluetoothDevice.EXTRA_PAIRING_CONTEXT), anyInt())).thenReturn(-1);
        return intent;
    }

    private static ScannerTestRig start(int sdk) {
        SdkInt.set(sdk);
        return new ScannerTestRig();
    }

    private static final class BondRun {
        BluetoothDevice remote;
        GattConnection connection;
        AtomicReference<TransactionResult> result;
        CountDownLatch done;
        Thread worker;
        BroadcastReceiver receiver;
    }
}
