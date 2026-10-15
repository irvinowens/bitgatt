/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import com.fitbit.bluetooth.fbgatt.engine.ConnectionEngine;

/**
 * Public face of the per-connection dispatcher. The queue itself is {@link ConnectionEngine}.
 * {@code getClientTransactionQueueController()} returns this shell so existing callers keep
 * compiling. There is no second queue.
 */
public class TransactionQueueController {

    private final ConnectionEngine engine;

    TransactionQueueController(GattConnection connection) {
        this.engine = BitGattComponents.connectionEngine(connection.getDevice().getName());
    }

    TransactionQueueController() {
        this.engine = BitGattComponents.serverEngine();
    }

    TransactionQueueController(String name) {
        this.engine = BitGattComponents.namedEngine(name);
    }

    void queueTransaction(Runnable tx) {
        engine.queueTransaction(tx);
    }

    /**
     * Will return the client thread being used to run transactions on this connection
     * @return The client transaction thread
     */
    Thread getTransactionThread() {
        return engine.getTransactionThread();
    }

    void clearQueue() {
        engine.clearQueue();
    }

    synchronized void start() {
        engine.start();
    }

    synchronized void stop() {
        engine.stop();
    }

    boolean isQueueThreadStopped() {
        return engine.isQueueThreadStopped();
    }

    ConnectionEngine engine() {
        return engine;
    }
}
