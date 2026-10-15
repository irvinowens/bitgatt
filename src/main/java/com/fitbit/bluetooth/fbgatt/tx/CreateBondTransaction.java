/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.tx;

import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice;
import com.fitbit.bluetooth.fbgatt.FitbitGatt;
import com.fitbit.bluetooth.fbgatt.GattConnection;
import com.fitbit.bluetooth.fbgatt.GattState;
import com.fitbit.bluetooth.fbgatt.GattTransaction;
import com.fitbit.bluetooth.fbgatt.GattTransactionCallback;
import com.fitbit.bluetooth.fbgatt.TransactionResult;
import com.fitbit.bluetooth.fbgatt.util.GattStatus;
import com.fitbit.bluetooth.fbgatt.util.GattUtils;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Will create a bluetooth bond.  The timeout will be extended to BOND_TIMEOUT value for this
 * transaction as sometimes a bond takes a while.  While this bond task is executing the transaction
 * queue for the device in question will be blocked, this does not stop the other devices from performing
 * operations.  Note: This implementation does not deal with devices that disconnect after bond,
 * that is business logic that is outside of the scope of this implementation.
 * <p>
 * Created by iowens on 8/28/18.
 */
public class CreateBondTransaction extends GattTransaction {
    /**
     * The transaction name
     */
    public static final String NAME = "CreateBondTransaction";
    /**
     * The timeout override value to be used for the bond transaction
     */
    private static final long BOND_TIMEOUT = TimeUnit.MINUTES.toMillis(2);

    private Context context;
    private final Object bondLock = new Object();
    private volatile boolean bondFinished;

    /**
     * Receiver for {@link BluetoothDevice#ACTION_BOND_STATE_CHANGED}, and on API 36+ for
     * key-missing and encryption-change broadcasts.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (autonomousRepair(intent)) {
                Timber.d("[%s] Autonomous re-pair in progress, not a bond failure", getDevice());
                return;
            }
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice extraDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                /*
                 * equals handled inside of {@link FitbitBluetoothDevice} for comparison to an
                 * {@link BluetoothDevice}
                 */
                //noinspection EqualsBetweenInconvertibleTypes
                if (getConnection().getDevice().equals(extraDevice)) {
                    int oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                    int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    GattUtils util = new GattUtils();
                    Timber.d("[%s] Bond state changed from %s to %s",
                            getConnection().getDevice(),
                            util.getBondStateDescription(oldState),
                            util.getBondStateDescription(newState));
                    switch (newState) {
                        case BluetoothDevice.BOND_BONDED:
                            Timber.d("[%s] Bond state changed to BONDED",getDevice());
                            bondSuccess();
                            break;
                        case BluetoothDevice.BOND_NONE:
                            Timber.w("[%s] Bond state changed to NONE",getDevice());
                            bondFailure();
                            break;
                        case BluetoothDevice.BOND_BONDING:
                            Timber.d("[%s] Bond state changed to BONDING",getDevice());
                            break;
                        default:
                            Timber.w("[%s] Bond state changed to UNKNOWN",getDevice());
                            break;
                    }
                } else {
                    Timber.i("[%s] Received Bond result, but for %s",
                            getConnection().getDevice(),
                            extraDevice);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                    && BluetoothDevice.ACTION_KEY_MISSING.equals(action)) {
                BluetoothDevice extraDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                //noinspection EqualsBetweenInconvertibleTypes
                if (getConnection().getDevice().equals(extraDevice)) {
                    Timber.w("[%s] Bond key is missing", getDevice());
                    bondFailure();
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                    && BluetoothDevice.ACTION_ENCRYPTION_CHANGE.equals(action)) {
                Timber.d("[%s] Encryption change observed during bond", getDevice());
            }
        }
    };
    /**
     * The transaction builder
     */
    private final TransactionResult.Builder builder;

    /**
     * Constructor
     *
     * @param connection      The {@link GattConnection} connection to attempt a bond
     * @param successEndState The success state for this transaction
     */
    public CreateBondTransaction(@Nullable GattConnection connection, GattState successEndState) {
        super(connection, successEndState);
        builder = new TransactionResult.Builder();
        // we're going to want a longer timeout for the bond
        setTimeout(BOND_TIMEOUT);
    }

    public CreateBondTransaction(@Nullable GattConnection connection, GattState successEndState, long timeoutMillis) {
        super(connection, successEndState);
        builder = new TransactionResult.Builder();
        // we're going to want a longer timeout for the bond
        setTimeout(timeoutMillis);
    }

    @Override
    protected void transaction(GattTransactionCallback callback) {
        super.transaction(callback);
        createBond();
    }

    private void createBond() {
        getConnection().setState(GattState.CREATING_BOND);
        Context context = FitbitGatt.getInstance().getAppContext();
        if (context == null) {
            Timber.w("[%s] Couldn't create the bond because the context was null", getDevice());
            bondFailure();
        } else {
            createBond(context);
        }
    }

    private void createBond(Context context) {
        String missing = GattUtils.missingConnectPermission(context);
        if (missing != null) {
            Timber.w("[%s] createBond refused, missing %s", getDevice(), missing);
            bondFailure();
            return;
        }
        FitbitBluetoothDevice device = getDevice();
        if (device == null) {
            Timber.w("[%s] Couldn't create the bond because device was null", device);
            bondFailure();
        } else {
            createBond(context, device);
            synchronized (bondLock) {
                long deadline = System.currentTimeMillis() + BOND_TIMEOUT;
                while (!bondFinished) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        bondLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Timber.e(e, "[%s] Well, the thread was interrupted, we will just let it go", device);
                        break;
                    }
                }
            }
        }
    }

    private void createBond(Context context, FitbitBluetoothDevice device) {
        this.context = context;
        IntentFilter bondFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            bondFilter.addAction(BluetoothDevice.ACTION_KEY_MISSING);
            bondFilter.addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(context, receiver, bondFilter, ContextCompat.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, bondFilter);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.getBtDevice().createBond(BluetoothDevice.TRANSPORT_LE);
        } else {
            device.getBtDevice().createBond();
        }
    }

    private boolean autonomousRepair(Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return false;
        }
        return intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_CONTEXT, -1) == BluetoothDevice.PAIRING_CONTEXT_REPAIRING;
    }

    private void releaseBondWait() {
        bondFinished = true;
        synchronized (bondLock) {
            bondLock.notifyAll();
        }
    }

    @VisibleForTesting
    void bondSuccess() {
        Timber.v("[%s] The bond attempt succeeded", getDevice());
        getConnection().setState(GattState.CREATE_BOND_SUCCESS);
        builder.transactionName(NAME)
                .gattState(getConnection().getGattState())
                .responseStatus(GattStatus.GATT_SUCCESS.ordinal())
                .resultStatus(TransactionResult.TransactionResultStatus.SUCCESS);
        if (callback != null) {
            callCallbackWithTransactionResultAndRelease(callback, builder.build());
        }
        getConnection().setState(GattState.IDLE);
        if (context != null) {
            context.unregisterReceiver(receiver);
        }
        releaseBondWait();
    }

    private void bondFailure() {
        Timber.v("[%s] The bond attempt failed", getDevice());
        getConnection().setState(GattState.CREATE_BOND_FAILURE);
        builder.transactionName(NAME)
                .gattState(getConnection().getGattState())
                .responseStatus(GattStatus.GATT_INTERNAL_ERROR.ordinal())
                .resultStatus(TransactionResult.TransactionResultStatus.FAILURE);
        if (callback != null) {
            callCallbackWithTransactionResultAndRelease(callback, builder.build());
        }
        getConnection().setState(GattState.IDLE);
        if (context != null) {
            context.unregisterReceiver(receiver);
        }
        releaseBondWait();
    }

    @Override
    protected void onGattClientTransactionTimeout(GattConnection connection) {
        super.onGattClientTransactionTimeout(connection);
        releaseBondWait();
    }

    @Override
    public String getName() {
        return NAME;
    }
}
