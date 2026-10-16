/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.bluetooth.le;

/**
 * Unit-test stand-in. The mockable jar's builder returns null, so extended advertise
 * parameters cannot be read back.
 */
public final class AdvertisingSetParameters {
    private final boolean connectable;
    private final boolean scannable;
    private final boolean legacy;
    private final int primaryPhy;
    private final int secondaryPhy;
    private final int interval;
    private final int txPowerLevel;

    private AdvertisingSetParameters(boolean connectable, boolean scannable, boolean legacy,
                                     int primaryPhy, int secondaryPhy, int interval, int txPowerLevel) {
        this.connectable = connectable;
        this.scannable = scannable;
        this.legacy = legacy;
        this.primaryPhy = primaryPhy;
        this.secondaryPhy = secondaryPhy;
        this.interval = interval;
        this.txPowerLevel = txPowerLevel;
    }

    public boolean isConnectable() {
        return connectable;
    }

    public boolean isScannable() {
        return scannable;
    }

    public boolean isLegacy() {
        return legacy;
    }

    public int getPrimaryPhy() {
        return primaryPhy;
    }

    public int getSecondaryPhy() {
        return secondaryPhy;
    }

    public int getInterval() {
        return interval;
    }

    public int getTxPowerLevel() {
        return txPowerLevel;
    }

    public static final class Builder {
        private boolean connectable;
        private boolean scannable;
        private boolean legacy;
        private int primaryPhy;
        private int secondaryPhy;
        private int interval;
        private int txPowerLevel;

        public Builder setConnectable(boolean connectable) {
            this.connectable = connectable;
            return this;
        }

        public Builder setScannable(boolean scannable) {
            this.scannable = scannable;
            return this;
        }

        public Builder setLegacyMode(boolean legacy) {
            this.legacy = legacy;
            return this;
        }

        public Builder setPrimaryPhy(int primaryPhy) {
            this.primaryPhy = primaryPhy;
            return this;
        }

        public Builder setSecondaryPhy(int secondaryPhy) {
            this.secondaryPhy = secondaryPhy;
            return this;
        }

        public Builder setInterval(int interval) {
            this.interval = interval;
            return this;
        }

        public Builder setTxPowerLevel(int txPowerLevel) {
            this.txPowerLevel = txPowerLevel;
            return this;
        }

        public AdvertisingSetParameters build() {
            return new AdvertisingSetParameters(connectable, scannable, legacy, primaryPhy, secondaryPhy, interval, txPowerLevel);
        }
    }
}
