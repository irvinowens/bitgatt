/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.bluetooth.le;

import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit-test stand-in. The mockable jar's builder returns null, so hosted service UUIDs
 * put on advertise data cannot be read back.
 */
public final class AdvertiseData {
    private final List<ParcelUuid> serviceUuids;

    private AdvertiseData(List<ParcelUuid> serviceUuids) {
        this.serviceUuids = serviceUuids;
    }

    public List<ParcelUuid> getServiceUuids() {
        return serviceUuids;
    }

    public static final class Builder {
        private final List<ParcelUuid> serviceUuids = new ArrayList<>();

        public Builder addServiceUuid(ParcelUuid uuid) {
            serviceUuids.add(uuid);
            return this;
        }

        public Builder addServiceData(ParcelUuid uuid, byte[] data) {
            return this;
        }

        public Builder addManufacturerData(int id, byte[] data) {
            return this;
        }

        public Builder setIncludeDeviceName(boolean include) {
            return this;
        }

        public Builder setIncludeTxPowerLevel(boolean include) {
            return this;
        }

        public AdvertiseData build() {
            return new AdvertiseData(Collections.unmodifiableList(new ArrayList<>(serviceUuids)));
        }
    }
}
