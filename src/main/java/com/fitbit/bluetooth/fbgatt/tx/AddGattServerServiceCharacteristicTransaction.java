/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.tx;

import com.fitbit.bluetooth.fbgatt.GattServerConnection;
import com.fitbit.bluetooth.fbgatt.GattServerTransaction;
import com.fitbit.bluetooth.fbgatt.GattState;
import com.fitbit.bluetooth.fbgatt.GattTransactionCallback;
import com.fitbit.bluetooth.fbgatt.TransactionResult;
import com.fitbit.bluetooth.fbgatt.descriptors.CharacteristicNotificationDescriptor;
import com.fitbit.bluetooth.fbgatt.util.GattDisconnectReason;
import com.fitbit.bluetooth.fbgatt.util.GattStatus;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import java.util.List;

import timber.log.Timber;

/**
 * Will add gatt server service characteristics to the local gatt server. A client characteristic
 * configuration descriptor (0x2902) is added only when the characteristic has notify or indicate.
 * A presentation-format descriptor (0x2904) is not added. The caller should check
 * before adding for the existence of this characteristic on the provided service or the result
 * will be a failure
 *
 * Created by iowens on 8/20/18.
 */
public class AddGattServerServiceCharacteristicTransaction extends GattServerTransaction {
    private static final String NAME = "AddGattServerServiceCharacteristicTransaction";

    private BluetoothGattService service;
    private BluetoothGattCharacteristic characteristic;

    public AddGattServerServiceCharacteristicTransaction(GattServerConnection server, GattState successEndState, BluetoothGattService service, BluetoothGattCharacteristic characteristic) {
        super(server, successEndState);
        this.service = service;
        this.characteristic = characteristic;
    }

    public AddGattServerServiceCharacteristicTransaction(GattServerConnection server, GattState successEndState, BluetoothGattService service, BluetoothGattCharacteristic characteristic, long timeoutMillis) {
        super(server, successEndState, timeoutMillis);
        this.service = service;
        this.characteristic = characteristic;
    }

    @Override
    protected void transaction(GattTransactionCallback callback) {
        super.transaction(callback);
        getGattServer().setState(GattState.ADDING_SERVICE_CHARACTERISTIC);
        if(getGattServer().getServer() == null) {
            mainThreadHandler.post(() -> {
                respondWithError("The GATT Server was not started yet, did you start the gatt?", callback);
            });
        } else {
            if(null != getGattServer().getServer().getService(service.getUuid())){
                if(doesCharacteristicAlreadyExistOnService(service, characteristic)) {
                    TransactionResult.Builder builder = new TransactionResult.Builder().transactionName(getName());
                    builder.responseStatus(GattDisconnectReason.getReasonForCode(GattDisconnectReason.GATT_CONN_NO_RESOURCES.getCode()).ordinal());
                    getGattServer().setState(GattState.ADD_SERVICE_CHARACTERISTIC_FAILURE);
                    Timber.w("The gatt service characteristic %s, is a duplicate, and could not be added to service: %s",
                            characteristic.getUuid(), service.getUuid());
                    builder.gattState(getGattServer().getGattState())
                            .serviceUuid(service.getUuid())
                            .characteristicUuid(characteristic.getUuid())
                            .resultStatus(TransactionResult.TransactionResultStatus.FAILURE);
                    callCallbackWithTransactionResultAndRelease(callback, builder.build());
                    getGattServer().setState(GattState.IDLE);
                    return;
                }
                addCriticalDescriptorsIfNecessary(characteristic);
                if(service.addCharacteristic(characteristic)) {
                    TransactionResult.Builder builder = new TransactionResult.Builder().transactionName(getName());
                    builder.responseStatus(GattStatus.GATT_SUCCESS.getCode());
                    getGattServer().setState(GattState.ADD_SERVICE_CHARACTERISTIC_SUCCESS);
                    Timber.e("The gatt service characteristic could not be added: %s", service.getUuid());
                    builder.gattState(getGattServer().getGattState())
                            .serviceUuid(service.getUuid())
                            .characteristicUuid(characteristic.getUuid())
                            .resultStatus(TransactionResult.TransactionResultStatus.SUCCESS);
                    callCallbackWithTransactionResultAndRelease(callback, builder.build());
                    getGattServer().setState(GattState.IDLE);
                } else {
                    respondWithError("Couldn't add the characteristic to the local gatt service", callback);
                }
            } else {
                respondWithError("The GATT Server was not hosting the service.", callback);
            }
        }
    }

    /**
     * Add the client characteristic configuration descriptor when the characteristic notifies or
     * indicates. A writable 0x2904 presentation-format descriptor is not synthesized.
     * @param characteristic The characteristic that we are adding to a service
     */
    private void addCriticalDescriptorsIfNecessary(BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        boolean signals = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        if (!signals) {
            return;
        }
        CharacteristicNotificationDescriptor notificationDescriptor = new CharacteristicNotificationDescriptor();
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(notificationDescriptor.getUuid());
        if(descriptor == null) {
            characteristic.addDescriptor(notificationDescriptor);
        }
    }

    private void respondWithError(String message, GattTransactionCallback callback) {
        TransactionResult.Builder builder = new TransactionResult.Builder().transactionName(getName());
        Timber.w(message);
        builder.responseStatus(GattDisconnectReason.getReasonForCode(GattDisconnectReason.GATT_CONN_NO_RESOURCES.getCode()).ordinal());
        getGattServer().setState(GattState.ADD_SERVICE_CHARACTERISTIC_DESCRIPTOR_FAILURE);
        Timber.e("The gatt service characteristic could not be added: %s", service.getUuid());
        builder.gattState(getGattServer().getGattState())
                .resultStatus(TransactionResult.TransactionResultStatus.FAILURE);
        callCallbackWithTransactionResultAndRelease(callback, builder.build());
        getGattServer().setState(GattState.IDLE);
    }

    /**
     * True if characteristic already exists on the service, false if it doesn't
     * @param service The local bluetooth gatt server service
     * @param characteristic The bluetooth gatt characteristic
     * @return true if the characteristic exists, false if it doesn't
     */

    private boolean doesCharacteristicAlreadyExistOnService(BluetoothGattService service, BluetoothGattCharacteristic characteristic) {
        return service.getCharacteristic(characteristic.getUuid()) != null;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
