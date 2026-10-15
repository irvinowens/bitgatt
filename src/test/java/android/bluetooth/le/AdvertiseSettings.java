/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.bluetooth.le;

/**
 * Unit-test stand-in. The mockable jar's builder methods return null, so a chained
 * {@code setConnectable().setAdvertiseMode().build()} cannot be read back.
 */
public final class AdvertiseSettings {
    public static final int ADVERTISE_MODE_LOW_POWER = 0;
    public static final int ADVERTISE_MODE_BALANCED = 1;
    public static final int ADVERTISE_MODE_LOW_LATENCY = 2;
    public static final int ADVERTISE_TX_POWER_ULTRA_LOW = 0;
    public static final int ADVERTISE_TX_POWER_LOW = 1;
    public static final int ADVERTISE_TX_POWER_MEDIUM = 2;
    public static final int ADVERTISE_TX_POWER_HIGH = 3;

    private final boolean connectable;
    private final int mode;
    private final int txPowerLevel;
    private final int timeout;

    private AdvertiseSettings(boolean connectable, int mode, int txPowerLevel, int timeout) {
        this.connectable = connectable;
        this.mode = mode;
        this.txPowerLevel = txPowerLevel;
        this.timeout = timeout;
    }

    public boolean isConnectable() {
        return connectable;
    }

    public int getMode() {
        return mode;
    }

    public int getTxPowerLevel() {
        return txPowerLevel;
    }

    public int getTimeout() {
        return timeout;
    }

    public static final class Builder {
        private boolean connectable;
        private int mode = ADVERTISE_MODE_LOW_POWER;
        private int txPowerLevel = ADVERTISE_TX_POWER_MEDIUM;
        private int timeout;

        public Builder setConnectable(boolean connectable) {
            this.connectable = connectable;
            return this;
        }

        public Builder setDiscoverable(boolean discoverable) {
            return this;
        }

        public Builder setAdvertiseMode(int mode) {
            this.mode = mode;
            return this;
        }

        public Builder setTxPowerLevel(int txPowerLevel) {
            this.txPowerLevel = txPowerLevel;
            return this;
        }

        public Builder setTimeout(int timeout) {
            this.timeout = timeout;
            return this;
        }

        public AdvertiseSettings build() {
            return new AdvertiseSettings(connectable, mode, txPowerLevel, timeout);
        }
    }
}
