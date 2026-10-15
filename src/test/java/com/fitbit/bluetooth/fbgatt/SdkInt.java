/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Unit tests run against an Android stub whose {@code SDK_INT} is not a compile-time constant.
 * Production bytecode reads the field, so tests can move it for an API band and put it back.
 */
final class SdkInt {
    private static final Object UNSAFE;
    private static final Object BASE;
    private static final long OFFSET;
    private static final Method PUT_INT;
    private static final int ORIGINAL;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            UNSAFE = unsafeField.get(null);
            Field sdk = Build.VERSION.class.getDeclaredField("SDK_INT");
            Method base = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method offset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            BASE = base.invoke(UNSAFE, sdk);
            OFFSET = (Long) offset.invoke(UNSAFE, sdk);
            PUT_INT = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            ORIGINAL = Build.VERSION.SDK_INT;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SdkInt() {
    }

    static int get() {
        return Build.VERSION.SDK_INT;
    }

    static void set(int sdkInt) {
        try {
            PUT_INT.invoke(UNSAFE, BASE, OFFSET, sdkInt);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void restore() {
        set(ORIGINAL);
    }
}
