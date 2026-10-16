/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.os;

import java.util.UUID;

/**
 * Unit-test stand-in. The mockable jar's constructor drops the UUID, so advertise data
 * cannot be compared with the hosted service.
 */
public final class ParcelUuid implements Parcelable {
    private final UUID uuid;

    public ParcelUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public static ParcelUuid fromString(String uuid) {
        return new ParcelUuid(UUID.fromString(uuid));
    }

    public UUID getUuid() {
        return uuid;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ParcelUuid)) {
            return false;
        }
        UUID theirs = ((ParcelUuid) other).uuid;
        return uuid == null ? theirs == null : uuid.equals(theirs);
    }

    @Override
    public int hashCode() {
        return uuid == null ? 0 : uuid.hashCode();
    }

    @Override
    public String toString() {
        return String.valueOf(uuid);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
