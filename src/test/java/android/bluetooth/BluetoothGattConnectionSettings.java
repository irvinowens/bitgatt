/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.bluetooth;

/**
 * Unit-test stand-in. The platform builder methods return null from the mockable jar,
 * so a connectGatt test cannot read the settings that were built.
 */
public final class BluetoothGattConnectionSettings {
    private final boolean autoConnect;
    private final int transport;

    private BluetoothGattConnectionSettings(boolean autoConnect, int transport) {
        this.autoConnect = autoConnect;
        this.transport = transport;
    }

    public int getTransport() {
        return transport;
    }

    public boolean isAutoConnectEnabled() {
        return autoConnect;
    }

    public boolean isAutomaticMtuEnabled() {
        return false;
    }

    public boolean isOpportunisticEnabled() {
        return false;
    }

    public static final class Builder {
        private boolean autoConnect;
        private int transport;

        public Builder() {
        }

        public Builder setAutoConnectEnabled(boolean enabled) {
            this.autoConnect = enabled;
            return this;
        }

        public Builder setAutomaticMtuEnabled(boolean enabled) {
            return this;
        }

        public Builder setOpportunisticEnabled(boolean enabled) {
            return this;
        }

        public Builder setTransport(int transport) {
            this.transport = transport;
            return this;
        }

        public BluetoothGattConnectionSettings build() {
            return new BluetoothGattConnectionSettings(autoConnect, transport);
        }
    }
}
