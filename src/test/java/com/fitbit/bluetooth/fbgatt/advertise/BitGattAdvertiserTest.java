/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.advertise;

import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice;
import com.fitbit.bluetooth.fbgatt.FitbitGatt;
import com.fitbit.bluetooth.fbgatt.GattConnection;
import com.fitbit.bluetooth.fbgatt.GattServerConnection;
import com.fitbit.bluetooth.fbgatt.GattState;
import com.fitbit.bluetooth.fbgatt.GattTransaction;
import com.fitbit.bluetooth.fbgatt.GattTransactionCallback;
import com.fitbit.bluetooth.fbgatt.TransactionResult;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ParcelUuid;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BitGattAdvertiserTest {

    private static final String ADDRESS = "02:00:00:00:00:21";
    private static final UUID HOSTED = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");

    private FitbitGatt gatt;
    private Context context;
    private Looper looper;
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser leAdvertiser;
    private BluetoothGattServer gattServer;
    private GattServerConnection serverConnection;
    private GattConnection clientConnection;

    @Before
    public void before() throws Exception {
        looper = mock(Looper.class);
        Thread thread = mock(Thread.class);
        when(thread.getName()).thenReturn("bitgatt-test");
        when(looper.getThread()).thenReturn(thread);
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getMainLooper()).thenReturn(looper);
        when(context.registerReceiver(any(), any())).thenReturn(new Intent("bitgatt-test"));
        when(context.checkPermission(any(String.class), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        gatt = FitbitGatt.getInstance();
        invoke(gatt, "unregisterAllGattEventListeners");
        invoke(gatt, "clearConnectionsMap");
        started().set(false);
        gatt.setGattServer(null);
        gatt.start(context);
        manager = mock(BluetoothManager.class);
        adapter = mock(BluetoothAdapter.class);
        leAdvertiser = mock(BluetoothLeAdvertiser.class);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.getBluetoothLeAdvertiser()).thenReturn(leAdvertiser);
        when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(false);
        when(manager.getAdapter()).thenReturn(adapter);
        when(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(manager);
    }

    @After
    public void after() throws Exception {
        if (gatt != null && gatt.getAdvertiser() != null) {
            gatt.getAdvertiser().stop();
        }
        if (clientConnection != null) {
            clientConnection.close();
        }
        if (serverConnection != null) {
            serverConnection.close();
        }
        if (gatt != null) {
            invoke(gatt, "unregisterAllGattEventListeners");
            invoke(gatt, "clearConnectionsMap");
            gatt.setGattServer(null);
            started().set(false);
        }
        sdkRestore();
    }

    @Test
    public void connectableStartWaitsUntilHostedServicesAreIdle() throws Exception {
        sdk(30);
        assertEquals(AdvertiseError.serverNotReady, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verifyNeverStarted();

        host(HOSTED);
        serverConnection.setState(GattState.CONNECTING);
        assertEquals(AdvertiseError.serverNotReady, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verifyNeverStarted();

        serverConnection.setState(GattState.IDLE);
        when(gattServer.getServices()).thenReturn(Collections.emptyList());
        assertEquals(AdvertiseError.serverNotReady, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verifyNeverStarted();

        List<BluetoothGattService> services = hostedServices(HOSTED);
        when(gattServer.getServices()).thenReturn(services);
        AdvertiseData other = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(UUID.randomUUID())).build();
        assertEquals(AdvertiseError.serverNotReady, gatt.getAdvertiser().start(requestWithData(other)));
        verifyNeverStarted();

        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        verify(leAdvertiser).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), any(AdvertiseCallback.class));
    }

    @Test
    public void bluetoothOffStopsAdvertisingAndCleansUpTheConnection() throws Exception {
        sdk(30);
        host(HOSTED);
        clientConnection = attachedClient();
        FitbitGatt.FitbitGattCallback listener = mock(FitbitGatt.FitbitGattCallback.class);
        gatt.registerGattEventListener(listener);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));

        gatt.bluetoothOff();

        verify(leAdvertiser).stopAdvertising(any(AdvertiseCallback.class));
        verify(listener).onAdvertiseStateChanged(false, null);
        assertFalse(gatt.getAdvertiser().isAdvertising());
        assertEquals(GattState.BT_OFF, clientConnection.getGattState());
        assertSame(clientConnection, gatt.getConnection(clientConnection.getDevice()));
        verify(gattServer, never()).close();
    }

    @Test
    public void legacyStartAdvertisingWhenExtendedAdvertisingIsUnsupported() throws Exception {
        host(HOSTED);
        sdk(26);
        when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(false);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        verify(leAdvertiser).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), any(AdvertiseCallback.class));
        verifyNeverSet();
    }

    @Test
    public void dataTooLargeIsMappedAndClearsTheActiveAdvertiser() throws Exception {
        sdk(30);
        host(HOSTED);
        FitbitGatt.FitbitGattCallback listener = mock(FitbitGatt.FitbitGattCallback.class);
        gatt.registerGattEventListener(listener);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        ArgumentCaptor<AdvertiseCallback> callback = ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(leAdvertiser).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), callback.capture());

        callback.getValue().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);

        verify(listener).onAdvertiseStateChanged(false, AdvertiseError.DATA_TOO_LARGE);
        assertFalse(gatt.getAdvertiser().isAdvertising());
        assertEquals(AdvertiseError.DATA_TOO_LARGE, AdvertiseError.fromPlatform(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE));
    }

    @Test
    public void secondStartReturnsAlreadyStarted() throws Exception {
        sdk(30);
        host(HOSTED);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        assertEquals(AdvertiseError.ALREADY_STARTED, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verify(leAdvertiser, times(1)).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), any(AdvertiseCallback.class));
    }

    @Test
    public void missingAdvertisePermissionIsExplicit() {
        sdk(31);
        when(context.checkPermission(eq(Manifest.permission.BLUETOOTH_ADVERTISE), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertEquals(AdvertiseError.missingPermission, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verifyNeverStarted();

        sdk(21);
        when(context.checkPermission(eq(Manifest.permission.BLUETOOTH_ADMIN), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertEquals(AdvertiseError.missingPermission, gatt.getAdvertiser().start(new AdvertiseRequest()));
        verifyNeverStarted();
    }

    @Test
    public void nonConnectableStartDoesNotOpenAServer() {
        sdk(30);
        gatt.setGattServer(null);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest(false)));
        verify(leAdvertiser).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), any(AdvertiseCallback.class));
        verify(manager, never()).openGattServer(any(Context.class), any(BluetoothGattServerCallback.class));
    }

    @Test
    public void startServerAndBluetoothOnDoNotAdvertise() throws Exception {
        sdk(30);
        CountDownLatch turnedOn = new CountDownLatch(1);
        FitbitGatt.FitbitGattCallback listener = mock(FitbitGatt.FitbitGattCallback.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            turnedOn.countDown();
            return null;
        }).when(listener).onBluetoothOn();
        gatt.registerGattEventListener(listener);

        gatt.bluetoothOn();

        assertTrue(turnedOn.await(2, TimeUnit.SECONDS));
        verifyNeverStarted();
    }

    @Test
    public void serverCloseStopsAdvertisingBeforeServicesAreCleared() throws Exception {
        sdk(30);
        host(HOSTED);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        Method close = GattServerConnection.class.getDeclaredMethod("closeGattServer");
        close.setAccessible(true);
        close.invoke(serverConnection);

        InOrder order = inOrder(leAdvertiser, gattServer);
        order.verify(leAdvertiser).stopAdvertising(any(AdvertiseCallback.class));
        order.verify(gattServer).clearServices();
        assertFalse(gatt.getAdvertiser().isAdvertising());
    }

    @Test
    public void advertiseFailureLeavesClientTransactionsAndBluetoothOffStillCleansUp() throws Exception {
        sdk(30);
        host(HOSTED);
        clientConnection = attachedClient();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<TransactionResult.TransactionResultStatus> inFlight = new AtomicReference<>();
        AtomicBoolean queuedRan = new AtomicBoolean(false);
        clientConnection.runTx(new HoldingTransaction(clientConnection, entered, release), result -> inFlight.set(result.getResultStatus()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        clientConnection.runTx(new GattTransaction(clientConnection, GattState.IDLE) {
            @Override
            protected void transaction(GattTransactionCallback callback) {
                callCallbackWithTransactionResultAndRelease(callback, success("queued"));
            }

            @Override
            public String getName() {
                return "queued";
            }
        }, result -> {
            queuedRan.set(result.getResultStatus() == TransactionResult.TransactionResultStatus.SUCCESS);
            secondDone.countDown();
        });
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        ArgumentCaptor<AdvertiseCallback> callback = ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(leAdvertiser).startAdvertising(any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), callback.capture());
        callback.getValue().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
        assertFalse(queuedRan.get());

        release.countDown();
        assertTrue(secondDone.await(2, TimeUnit.SECONDS));
        assertEquals(TransactionResult.TransactionResultStatus.SUCCESS, inFlight.get());
        assertTrue(queuedRan.get());
        assertEquals(GattState.IDLE, clientConnection.getGattState());

        org.mockito.Mockito.clearInvocations(leAdvertiser);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        gatt.bluetoothOff();
        verify(leAdvertiser).stopAdvertising(any(AdvertiseCallback.class));
        assertEquals(GattState.BT_OFF, clientConnection.getGattState());
        assertSame(clientConnection, gatt.getConnection(clientConnection.getDevice()));
    }

    @Test
    public void extendedAdvertisingUsesAdvertisingSetOnlyWhenTheControllerSupportsIt() throws Exception {
        host(HOSTED);
        for (int sdk : new int[] {26, 37}) {
            sdk(sdk);
            when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(true);
            gatt.getAdvertiser().stop();
            org.mockito.Mockito.clearInvocations(leAdvertiser);
            assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
            verify(leAdvertiser).startAdvertisingSet(
                    any(AdvertisingSetParameters.class),
                    any(AdvertiseData.class),
                    isNull(),
                    isNull(),
                    isNull(),
                    any(AdvertisingSetCallback.class));
            verify(leAdvertiser, never()).startAdvertising(
                    any(AdvertiseSettings.class), any(AdvertiseData.class), nullable(AdvertiseData.class), any(AdvertiseCallback.class));
        }
        for (int sdk : new int[] {21, 25}) {
            sdk(sdk);
            when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(true);
            gatt.getAdvertiser().stop();
            org.mockito.Mockito.clearInvocations(leAdvertiser);
            assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
            verify(leAdvertiser).startAdvertising(
                    any(AdvertiseSettings.class), any(AdvertiseData.class), isNull(), any(AdvertiseCallback.class));
            verifyNeverSet();
        }
    }

    @Test
    public void defaultRequestUsesBalancedLegacyHostedServicesAndNoPeriodicParameters() throws Exception {
        host(HOSTED);
        sdk(30);
        when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(false);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        ArgumentCaptor<AdvertiseSettings> settings = ArgumentCaptor.forClass(AdvertiseSettings.class);
        ArgumentCaptor<AdvertiseData> data = ArgumentCaptor.forClass(AdvertiseData.class);
        verify(leAdvertiser).startAdvertising(settings.capture(), data.capture(), isNull(), any(AdvertiseCallback.class));
        assertTrue(settings.getValue().isConnectable());
        assertEquals(AdvertiseSettings.ADVERTISE_MODE_BALANCED, settings.getValue().getMode());
        assertEquals(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM, settings.getValue().getTxPowerLevel());
        assertEquals(0, settings.getValue().getTimeout());
        assertEquals(Collections.singletonList(new ParcelUuid(HOSTED)), data.getValue().getServiceUuids());

        gatt.getAdvertiser().stop();
        org.mockito.Mockito.clearInvocations(leAdvertiser);
        sdk(26);
        when(adapter.isLeExtendedAdvertisingSupported()).thenReturn(true);
        assertNull(gatt.getAdvertiser().start(new AdvertiseRequest()));
        ArgumentCaptor<AdvertisingSetParameters> parameters = ArgumentCaptor.forClass(AdvertisingSetParameters.class);
        ArgumentCaptor<AdvertiseData> extendedData = ArgumentCaptor.forClass(AdvertiseData.class);
        verify(leAdvertiser).startAdvertisingSet(
                parameters.capture(),
                extendedData.capture(),
                isNull(AdvertiseData.class),
                isNull(PeriodicAdvertisingParameters.class),
                isNull(AdvertiseData.class),
                any(AdvertisingSetCallback.class));
        assertTrue(parameters.getValue().isConnectable());
        assertTrue(parameters.getValue().isLegacy());
        assertEquals(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM, parameters.getValue().getTxPowerLevel());
        assertEquals(BluetoothDevice.PHY_LE_1M, parameters.getValue().getPrimaryPhy());
        assertEquals(Collections.singletonList(new ParcelUuid(HOSTED)), extendedData.getValue().getServiceUuids());
    }

    private void host(UUID uuid) throws Exception {
        gattServer = mock(BluetoothGattServer.class);
        List<BluetoothGattService> services = hostedServices(uuid);
        when(gattServer.getServices()).thenReturn(services);
        Constructor<GattServerConnection> ctor = GattServerConnection.class.getDeclaredConstructor(BluetoothGattServer.class, Looper.class);
        ctor.setAccessible(true);
        serverConnection = ctor.newInstance(gattServer, looper);
        serverConnection.setState(GattState.IDLE);
        gatt.setGattServer(serverConnection);
    }

    private static List<BluetoothGattService> hostedServices(UUID uuid) {
        BluetoothGattService service = mock(BluetoothGattService.class);
        when(service.getUuid()).thenReturn(uuid);
        List<BluetoothGattService> services = new ArrayList<>();
        services.add(service);
        return services;
    }

    private GattConnection attachedClient() throws Exception {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(ADDRESS);
        when(device.toString()).thenReturn(ADDRESS);
        Constructor<FitbitBluetoothDevice> ctor = FitbitBluetoothDevice.class.getDeclaredConstructor(String.class, String.class, BluetoothDevice.class);
        ctor.setAccessible(true);
        FitbitBluetoothDevice fitbitDevice = ctor.newInstance(ADDRESS, "advertiser", device);
        GattConnection connection = new GattConnection(fitbitDevice, looper);
        connection.setState(GattState.IDLE);
        gatt.putConnectionIntoDevices(fitbitDevice, connection);
        return connection;
    }

    private static AdvertiseRequest requestWithData(AdvertiseData data) {
        return new AdvertiseRequest(
                true,
                false,
                true,
                BluetoothDevice.PHY_LE_1M,
                BluetoothDevice.PHY_LE_1M,
                400,
                AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                data,
                null);
    }

    private void verifyNeverStarted() {
        verify(leAdvertiser, never()).startAdvertising(
                any(AdvertiseSettings.class), any(AdvertiseData.class), nullable(AdvertiseData.class), any(AdvertiseCallback.class));
        verify(leAdvertiser, never()).startAdvertising(
                any(AdvertiseSettings.class), any(AdvertiseData.class), any(AdvertiseCallback.class));
        verifyNeverSet();
    }

    private void verifyNeverSet() {
        verify(leAdvertiser, never()).startAdvertisingSet(
                nullable(AdvertisingSetParameters.class),
                nullable(AdvertiseData.class),
                nullable(AdvertiseData.class),
                nullable(PeriodicAdvertisingParameters.class),
                nullable(AdvertiseData.class),
                nullable(AdvertisingSetCallback.class));
    }

    private java.util.concurrent.atomic.AtomicBoolean started() throws Exception {
        Field field = FitbitGatt.class.getDeclaredField("isStarted");
        field.setAccessible(true);
        return (java.util.concurrent.atomic.AtomicBoolean) field.get(gatt);
    }

    private static void sdk(int level) {
        try {
            Method method = Class.forName("com.fitbit.bluetooth.fbgatt.SdkInt").getDeclaredMethod("set", int.class);
            method.setAccessible(true);
            method.invoke(null, level);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void sdkRestore() {
        try {
            Method method = Class.forName("com.fitbit.bluetooth.fbgatt.SdkInt").getDeclaredMethod("restore");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(FitbitGatt target, String name) throws Exception {
        Method method = FitbitGatt.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static TransactionResult success(String name) {
        return new TransactionResult.Builder()
                .transactionName(name)
                .resultStatus(TransactionResult.TransactionResultStatus.SUCCESS)
                .gattState(GattState.IDLE)
                .build();
    }

    private static final class HoldingTransaction extends GattTransaction {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        HoldingTransaction(GattConnection connection, CountDownLatch entered, CountDownLatch release) {
            super(connection, GattState.IDLE);
            setTimeout(2000);
            this.entered = entered;
            this.release = release;
        }

        @Override
        protected void transaction(GattTransactionCallback callback) {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            callCallbackWithTransactionResultAndRelease(callback, success("holding"));
        }

        @Override
        public String getName() {
            return "holding";
        }
    }
}
