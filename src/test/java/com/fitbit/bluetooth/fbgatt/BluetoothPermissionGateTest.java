/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.tx.CreateBondTransaction;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;

import java.util.Collections;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BluetoothPermissionGateTest {

    private ScannerTestRig rig;

    @After
    public void after() {
        if (rig != null) {
            rig.close();
        }
    }

    @Test
    public void scanOnApi21And30FailsWithoutLocationAndDoesNotCallThePlatform() {
        assertScanRefused(21);
        assertScanRefused(30);
    }

    @Test
    public void scanOnApi31And37FailsWithoutBluetoothScan() {
        assertScanRefusedForScanPermission(31);
        assertScanRefusedForScanPermission(37);
    }

    @Test
    public void scanOnApi31And37IsAllowedWhenScanIsGrantedAndLocationIsDenied() {
        assertScanAllowedWithoutLocation(31);
        assertScanAllowedWithoutLocation(37);
    }

    @Test
    public void connectOpenServerAndCreateBondFailWithoutBluetoothConnect() throws Exception {
        assertConnectRefused(31);
        assertConnectRefused(37);
        assertServerRefused(31);
        assertServerRefused(37);
        assertBondRefused(31);
        assertBondRefused(37);
    }

    @Test
    public void noPathCallsRequestPermissions() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".kt");
            })::iterator) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(file + " calls requestPermissions", source.contains("requestPermissions"));
            }
        }
    }

    private void assertScanRefused(int sdk) {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.grant(Manifest.permission.BLUETOOTH);
        rig.deny(Manifest.permission.ACCESS_FINE_LOCATION);
        ScannerInterface scanner = mock(ScannerInterface.class);
        when(scanner.isBluetoothEnabled()).thenReturn(true);
        rig.scanner.injectMockScanner(scanner);
        assertFalse(rig.scanner.startPeriodicScan(rig.context));
        verify(scanner, never()).startScan(any(), any(), any(android.bluetooth.le.ScanCallback.class));
        rig.close();
        rig = null;
    }

    private void assertScanRefusedForScanPermission(int sdk) {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.deny(Manifest.permission.BLUETOOTH_SCAN);
        rig.deny(Manifest.permission.ACCESS_FINE_LOCATION);
        ScannerInterface scanner = mock(ScannerInterface.class);
        when(scanner.isBluetoothEnabled()).thenReturn(true);
        rig.scanner.injectMockScanner(scanner);
        assertFalse(rig.scanner.startPeriodicScan(rig.context));
        verify(scanner, never()).startScan(any(), any(), any(android.bluetooth.le.ScanCallback.class));
        rig.close();
        rig = null;
    }

    private void assertScanAllowedWithoutLocation(int sdk) {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.grant(Manifest.permission.BLUETOOTH_SCAN);
        rig.deny(Manifest.permission.ACCESS_FINE_LOCATION);
        rig.scanner.setScanFilters(Collections.singletonList(new ScanFilter.Builder().setDeviceName("bitgatt").build()));
        ScannerInterface scanner = mock(ScannerInterface.class);
        when(scanner.isBluetoothEnabled()).thenReturn(true);
        rig.scanner.injectMockScanner(scanner);
        assertTrue(rig.scanner.startPeriodicScan(rig.context));
        verify(scanner).startScan(any(), any(), any(android.bluetooth.le.ScanCallback.class));
        rig.close();
        rig = null;
    }

    private void assertConnectRefused(int sdk) {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.deny(Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothDevice bt = mock(BluetoothDevice.class, invocation -> {
            if ("connectGatt".equals(invocation.getMethod().getName()) || "connect".equals(invocation.getMethod().getName())) {
                throw new AssertionError(invocation.getMethod().getName());
            }
            if ("getAddress".equals(invocation.getMethod().getName())) {
                return "AA:BB:CC:DD:EE:01";
            }
            if ("getName".equals(invocation.getMethod().getName())) {
                return "gate";
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        GattConnection connection = new GattConnection(new FitbitBluetoothDevice(bt), rig.looper);
        connection.setMockMode(false);
        assertFalse(connection.connect());
        assertEquals(GattState.FAILURE_CONNECTING, connection.getGattState());
        verify(bt, never()).connectGatt(any(Context.class), anyBoolean(), any(BluetoothGattCallback.class));
        rig.close();
        rig = null;
    }

    private void assertServerRefused(int sdk) throws Exception {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.deny(Manifest.permission.BLUETOOTH_CONNECT);
        Class<?> callbackType = Class.forName("com.fitbit.bluetooth.fbgatt.FitbitGatt$OpenGattServerCallback");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean opened = new AtomicBoolean(true);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if ("onGattServerStatus".equals(method.getName())) {
                        opened.set((Boolean) args[0]);
                        latch.countDown();
                    }
                    return null;
                });
        Method startServer = FitbitGatt.class.getDeclaredMethod("startServer", Context.class, callbackType);
        startServer.setAccessible(true);
        startServer.invoke(rig.gatt, rig.context, callback);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertFalse(opened.get());
        verify((BluetoothManager) rig.bluetoothManager, never()).openGattServer(any(), any());
        rig.close();
        rig = null;
    }

    private void assertBondRefused(int sdk) throws Exception {
        SdkInt.set(sdk);
        rig = new ScannerTestRig();
        rig.deny(Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothDevice bt = rig.device("AA:BB:CC:DD:EE:02");
        GattConnection connection = new GattConnection(new FitbitBluetoothDevice(bt), rig.looper);
        CreateBondTransaction transaction = new CreateBondTransaction(connection, GattState.CREATE_BOND_SUCCESS);
        AtomicBoolean failed = new AtomicBoolean(false);
        Method run = CreateBondTransaction.class.getDeclaredMethod("transaction", GattTransactionCallback.class);
        run.setAccessible(true);
        run.invoke(transaction, (GattTransactionCallback) result -> {
            failed.set(result.resultStatus == TransactionResult.TransactionResultStatus.FAILURE);
        });
        assertTrue(failed.get());
        assertEquals(GattState.IDLE, connection.getGattState());
        verify(bt, never()).createBond();
        rig.close();
        rig = null;
    }
}
