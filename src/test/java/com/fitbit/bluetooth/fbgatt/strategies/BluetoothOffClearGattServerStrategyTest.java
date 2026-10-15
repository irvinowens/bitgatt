/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.strategies;

import com.fitbit.bluetooth.fbgatt.AndroidDevice;
import com.fitbit.bluetooth.fbgatt.FitbitGatt;
import com.fitbit.bluetooth.fbgatt.GattServerConnection;
import com.fitbit.bluetooth.fbgatt.Situation;
import com.fitbit.bluetooth.fbgatt.StrategyProvider;

import android.bluetooth.BluetoothGattServer;
import android.os.Build;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BluetoothOffClearGattServerStrategyTest {

    @After
    public void after() {
        FitbitGatt.getInstance().setGattServer(null);
    }

    @Test
    public void samsungMatchClearsServicesAndDoesNotCloseTheServer() throws Exception {
        String original = Build.MANUFACTURER;
        setManufacturer("Samsung");
        try {
            Looper looper = mock(Looper.class);
            when(looper.getThread()).thenReturn(mock(Thread.class));
            BluetoothGattServer server = mock(BluetoothGattServer.class);
            Constructor<GattServerConnection> constructor = GattServerConnection.class.getDeclaredConstructor(BluetoothGattServer.class, Looper.class);
            constructor.setAccessible(true);
            FitbitGatt.getInstance().setGattServer(constructor.newInstance(server, looper));
            AndroidDevice samsung = new AndroidDevice.Builder().manufacturerName("Samsung").build();
            Strategy strategy = new StrategyProvider().getStrategyForPhoneAndGattConnection(
                    samsung,
                    null,
                    Situation.CLEAR_GATT_SERVER_SERVICES_DEVICE_FUNKY_BT_IMPL);
            assertTrue(strategy instanceof BluetoothOffClearGattServerStrategy);
            strategy.applyStrategy();
            verify(server).clearServices();
            verify(server, never()).close();
        } finally {
            setManufacturer(original);
        }
    }

    private static void setManufacturer(String manufacturer) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field manufacturerField = Build.class.getDeclaredField("MANUFACTURER");
        Method base = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method offset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        putObject.invoke(unsafe, base.invoke(unsafe, manufacturerField), offset.invoke(unsafe, manufacturerField), manufacturer);
    }
}
