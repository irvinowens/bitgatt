/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import java.util.HashMap
import java.util.Locale

/**
 * An android device, which is an encapsulation of the build variables in a simple comparable
 * object.  The intended use is for a strategy consumer to create a target android device that
 * will be compared to the values from the present android mobile device.
 *
 * The comparator will consider equivalence to be any defined values in both objects to be matching
 * absent case.
 */
class AndroidDevice internal constructor(
    manufacturerName: String?,
    deviceModel: String?,
    apiLevel: Int?,
    radioVersion: String?,
    board: String?,
    bootloader: String?,
    brand: String?,
    display: String?,
    fingerprint: String?,
    device: String?,
    hardware: String?,
    host: String?,
    id: String?,
    product: String?,
    type: String?,
    private val numberOfSetProperties: Int
) {
    private val androidProperties = HashMap<String, Any?>()

    init {
        androidProperties[MANUFACTURER_NAME_KEY] = manufacturerName
        androidProperties[DEVICE_MODEL_KEY] = deviceModel
        androidProperties[API_LEVEL_KEY] = apiLevel
        androidProperties[RADIO_VERSION_KEY] = radioVersion
        androidProperties[BOARD_KEY] = board
        androidProperties[BOOTLOADER_KEY] = bootloader
        androidProperties[BRAND_KEY] = brand
        androidProperties[DISPLAY_KEY] = display
        androidProperties[FINGERPRINT_KEY] = fingerprint
        androidProperties[DEVICE_KEY] = device
        androidProperties[HARDWARE_KEY] = hardware
        androidProperties[HOST_KEY] = host
        androidProperties[ID_KEY] = id
        androidProperties[PRODUCT_KEY] = product
        androidProperties[TYPE_KEY] = type
    }

    fun getAndroidProperties(): HashMap<String, Any?> = androidProperties

    /** The manufacturer name of the android device */
    fun getManufacturerName(): String? = androidProperties[MANUFACTURER_NAME_KEY] as String?

    /** The model string of the android device */
    fun getDeviceModel(): String? = androidProperties[DEVICE_MODEL_KEY] as String?

    /** The api level of the android device */
    fun getApiLevel(): Int? = androidProperties[API_LEVEL_KEY] as Int?

    /** The radio version string of the android device */
    fun getRadioVersion(): String? = androidProperties[RADIO_VERSION_KEY] as String?

    /** The board string of the android device */
    fun getBoard(): String? = androidProperties[BOARD_KEY] as String?

    /** The bootloader string of the android device */
    fun getBootloader(): String? = androidProperties[BOOTLOADER_KEY] as String?

    /** The user brand string of the android device */
    fun getBrand(): String? = androidProperties[BRAND_KEY] as String?

    /** The display string of the android device */
    fun getDisplay(): String? = androidProperties[DISPLAY_KEY] as String?

    /** The device fingerprint string of the android device */
    fun getFingerprint(): String? = androidProperties[FINGERPRINT_KEY] as String?

    /** The device string of the android device */
    fun getDevice(): String? = androidProperties[DEVICE_KEY] as String?

    /** The hardware string of the android device */
    fun getHardware(): String? = androidProperties[HARDWARE_KEY] as String?

    /** The host string of the android device */
    fun getHost(): String? = androidProperties[HOST_KEY] as String?

    /** The id string of the android device */
    fun getId(): String? = androidProperties[ID_KEY] as String?

    /** The product name string of the android device */
    fun getProduct(): String? = androidProperties[PRODUCT_KEY] as String?

    /** The type string of the android device */
    fun getType(): String? = androidProperties[TYPE_KEY] as String?

    override fun equals(other: Any?): Boolean {
        if (other is AndroidDevice) {
            return androidProperties.entries.size == other.androidProperties.entries.size &&
                androidProperties.entries.containsAll(other.androidProperties.entries)
        }
        return super.equals(other)
    }

    override fun hashCode(): Int = super.hashCode()

    override fun toString(): String {
        return String.format(
            Locale.ENGLISH,
            "AndroidDevice[%s, %s, %s, %s, %s, %s]",
            getManufacturerName(),
            getDeviceModel(),
            getApiLevel(),
            getHardware(),
            getBrand(),
            getBoard()
        )
    }

    /**
     * Builder for an Android device
     */
    class Builder {
        private var manufacturerName: String? = null
        private var deviceModel: String? = null
        private var apiLevel: Int? = null
        private var radioVersion: String? = null
        private var board: String? = null
        private var bootloader: String? = null
        private var brand: String? = null
        private var display: String? = null
        private var fingerprint: String? = null
        private var device: String? = null
        private var hardware: String? = null
        private var host: String? = null
        private var id: String? = null
        private var product: String? = null
        private var type: String? = null
        private var numberOfParamsSet = 0

        fun manufacturerName(manufacturerName: String?): Builder {
            this.manufacturerName = manufacturerName
            if (this.manufacturerName != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun deviceModel(deviceModel: String?): Builder {
            this.deviceModel = deviceModel
            if (this.deviceModel != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun apiLevel(apiLevel: Int?): Builder {
            this.apiLevel = apiLevel
            if (this.apiLevel != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun radioVersion(radioVersion: String?): Builder {
            this.radioVersion = radioVersion
            if (this.radioVersion != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun board(board: String?): Builder {
            this.board = board
            if (this.board != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun bootloader(bootloader: String?): Builder {
            this.bootloader = bootloader
            if (this.bootloader != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun brand(brand: String?): Builder {
            this.brand = brand
            if (this.brand != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun display(display: String?): Builder {
            this.display = display
            if (this.display != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun device(device: String?): Builder {
            this.device = device
            if (this.device != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun fingerprint(fingerprint: String?): Builder {
            this.fingerprint = fingerprint
            if (this.fingerprint != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun hardware(hardware: String?): Builder {
            this.hardware = hardware
            if (this.hardware != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun host(host: String?): Builder {
            this.host = host
            if (this.host != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun id(id: String?): Builder {
            this.id = id
            if (this.id != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun product(product: String?): Builder {
            this.product = product
            if (this.product != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun type(type: String?): Builder {
            this.type = type
            if (this.type != null) {
                numberOfParamsSet++
            }
            return this
        }

        fun build(): AndroidDevice {
            return AndroidDevice(
                manufacturerName, deviceModel, apiLevel, radioVersion, board,
                bootloader, brand, display, fingerprint, device, hardware, host, id, product, type, numberOfParamsSet
            )
        }
    }

    companion object {
        private const val MANUFACTURER_NAME_KEY = "manufacturerName"
        private const val DEVICE_MODEL_KEY = "deviceModel"
        private const val API_LEVEL_KEY = "apiLevel"
        private const val RADIO_VERSION_KEY = "radioVersion"
        private const val BOARD_KEY = "board"
        private const val BOOTLOADER_KEY = "bootloader"
        private const val BRAND_KEY = "brand"
        private const val DISPLAY_KEY = "display"
        private const val FINGERPRINT_KEY = "fingerprint"
        private const val DEVICE_KEY = "device"
        private const val HARDWARE_KEY = "hardware"
        private const val HOST_KEY = "host"
        private const val ID_KEY = "id"
        private const val PRODUCT_KEY = "product"
        private const val TYPE_KEY = "type"
    }
}
