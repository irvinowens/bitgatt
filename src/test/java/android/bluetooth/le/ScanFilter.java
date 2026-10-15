/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.bluetooth.le;

import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unit-test stand-in. The platform jar's {@code ScanFilter.Builder} throws {@code Stub!},
 * and the mockable jar returns null from every builder method, so a chained
 * {@code setDeviceAddress().build()} cannot be observed. This type records the address
 * and the other fields the scanner actually sets.
 */
public final class ScanFilter implements Parcelable {
    private final String deviceAddress;
    private final String deviceName;
    private final ParcelUuid serviceUuid;
    private final ParcelUuid serviceUuidMask;
    private final ParcelUuid serviceDataUuid;
    private final byte[] serviceData;
    private final byte[] serviceDataMask;

    private ScanFilter(String deviceAddress, String deviceName, ParcelUuid serviceUuid,
                        ParcelUuid serviceUuidMask, ParcelUuid serviceDataUuid,
                        byte[] serviceData, byte[] serviceDataMask) {
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.serviceUuid = serviceUuid;
        this.serviceUuidMask = serviceUuidMask;
        this.serviceDataUuid = serviceDataUuid;
        this.serviceData = serviceData;
        this.serviceDataMask = serviceDataMask;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public ParcelUuid getServiceUuid() {
        return serviceUuid;
    }

    public ParcelUuid getServiceUuidMask() {
        return serviceUuidMask;
    }

    public ParcelUuid getServiceDataUuid() {
        return serviceDataUuid;
    }

    public byte[] getServiceData() {
        return serviceData;
    }

    public byte[] getServiceDataMask() {
        return serviceDataMask;
    }

    public boolean matches(ScanResult result) {
        if (result == null) {
            return false;
        }
        if (deviceAddress != null) {
            BluetoothDevice device = result.getDevice();
            return device != null && deviceAddress.equals(device.getAddress());
        }
        return deviceName == null && serviceUuid == null && serviceDataUuid == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScanFilter)) {
            return false;
        }
        ScanFilter that = (ScanFilter) other;
        return Objects.equals(deviceAddress, that.deviceAddress)
                && Objects.equals(deviceName, that.deviceName)
                && Objects.equals(serviceUuid, that.serviceUuid)
                && Objects.equals(serviceUuidMask, that.serviceUuidMask)
                && Objects.equals(serviceDataUuid, that.serviceDataUuid)
                && Arrays.equals(serviceData, that.serviceData)
                && Arrays.equals(serviceDataMask, that.serviceDataMask);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceAddress, deviceName, serviceUuid, serviceUuidMask, serviceDataUuid,
                Arrays.hashCode(serviceData), Arrays.hashCode(serviceDataMask));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    public static final Creator<ScanFilter> CREATOR = new Creator<ScanFilter>() {
        @Override
        public ScanFilter createFromParcel(Parcel source) {
            return new Builder().build();
        }

        @Override
        public ScanFilter[] newArray(int size) {
            return new ScanFilter[size];
        }
    };

    public static final class Builder {
        private String deviceAddress;
        private String deviceName;
        private ParcelUuid serviceUuid;
        private ParcelUuid serviceUuidMask;
        private ParcelUuid serviceDataUuid;
        private byte[] serviceData;
        private byte[] serviceDataMask;

        public Builder setDeviceAddress(String deviceAddress) {
            this.deviceAddress = deviceAddress;
            return this;
        }

        public Builder setDeviceName(String deviceName) {
            this.deviceName = deviceName;
            return this;
        }

        public Builder setServiceUuid(ParcelUuid serviceUuid) {
            this.serviceUuid = serviceUuid;
            return this;
        }

        public Builder setServiceUuid(ParcelUuid serviceUuid, ParcelUuid serviceUuidMask) {
            this.serviceUuid = serviceUuid;
            this.serviceUuidMask = serviceUuidMask;
            return this;
        }

        public Builder setServiceData(ParcelUuid serviceDataUuid, byte[] serviceData) {
            return setServiceData(serviceDataUuid, serviceData, null);
        }

        public Builder setServiceData(ParcelUuid serviceDataUuid, byte[] serviceData, byte[] serviceDataMask) {
            this.serviceDataUuid = serviceDataUuid;
            this.serviceData = serviceData;
            this.serviceDataMask = serviceDataMask;
            return this;
        }

        public Builder setManufacturerData(int manufacturerId, byte[] manufacturerData) {
            return this;
        }

        public Builder setManufacturerData(int manufacturerId, byte[] manufacturerData, byte[] manufacturerDataMask) {
            return this;
        }

        public ScanFilter build() {
            return new ScanFilter(deviceAddress, deviceName, serviceUuid, serviceUuidMask,
                    serviceDataUuid, serviceData, serviceDataMask);
        }
    }
}
