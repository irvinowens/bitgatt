/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import timber.log.Timber;

/**
 * Composite transaction that will run all transactions delivered to it.  In this case
 * the success end state is irrelevant, it will be looking to be idle.  If a transaction in the
 * stack fails, the execution will be halted with a transaction result that indicates failure
 * then will return all execution results that have happened so far.
 *
 * Created by iowens on 04/25/19.
 */

public class CompositeClientTransaction extends GattTransaction implements Closeable {
    public static final String NAME = "CompositeClientTransaction";
    private List<GattTransaction> transactionList;
    private GattTransactionCallback finalCallback;
    private ArrayList<TransactionResult> results = new ArrayList<>();

    public CompositeClientTransaction(@Nullable GattConnection connection, @NonNull List<GattTransaction> transactionList) {
        super(connection, GattState.IDLE);
        this.transactionList = transactionList;
        // setting the transaction timeout to the aggregate
        if(!transactionList.isEmpty()) {
            setTimeout(DEFAULT_GATT_TRANSACTION_TIMEOUT * transactionList.size());
        }
    }

    @Override
    protected void transaction(GattTransactionCallback callback) {
        super.transaction(callback);
        this.finalCallback = callback;
        // Children run on this connection's dispatcher. commit blocks until each child
        // finishes, so the parent stays held and there is no nested queue.
        runChildren();
    }

    private void runChildren() {
        if (transactionList.isEmpty()) {
            Timber.w("[%s] Transaction list was empty", getDevice());
            finish(TransactionResult.TransactionResultStatus.FAILURE);
            return;
        }
        for (int index = 0; index < transactionList.size(); index++) {
            final TransactionResult[] slot = new TransactionResult[1];
            transactionList.get(index).commit(result -> slot[0] = result);
            TransactionResult result = slot[0];
            if (result == null) {
                result = new TransactionResult.Builder()
                        .transactionName(NAME)
                        .resultStatus(TransactionResult.TransactionResultStatus.FAILURE)
                        .build();
            }
            results.add(result);
            Timber.v("[%s] Transaction result: %s", getDevice(), result);
            if (!result.getResultStatus().equals(TransactionResult.TransactionResultStatus.SUCCESS)) {
                Timber.w("[%s] Transaction %s failed with result: %s, aborting chain", getDevice(), result.getTransactionName(), result);
                finish(TransactionResult.TransactionResultStatus.FAILURE);
                return;
            }
            Timber.v("[%s] Transaction %s was successful, moving on to index: %d", getDevice(), result, index + 1);
        }
        Timber.d("[%s] Finished running all transactions successfully, last result: %s", getDevice(), results.get(results.size() - 1));
        finish(TransactionResult.TransactionResultStatus.SUCCESS);
    }

    private void finish(TransactionResult.TransactionResultStatus status) {
        TransactionResult.Builder builder = new TransactionResult.Builder();
        builder.resultStatus(status);
        builder.transactionName(NAME).addTransactionResults(results);
        callCallbackWithTransactionResultAndRelease(finalCallback, builder.build());
    }

    @Override
    protected void onGattClientTransactionTimeout(GattConnection connection) {
        super.onGattClientTransactionTimeout(connection);
        // let's be extra sure to close out here
        close();
    }

    @Override
    public void callCallbackWithTransactionResultAndRelease(GattTransactionCallback callback, TransactionResult result) {
        try {
            super.callCallbackWithTransactionResultAndRelease(callback, result);
        } finally {
            close();
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void close() {
        // The nested queue is gone. The connection dispatcher stays up.
    }
}
