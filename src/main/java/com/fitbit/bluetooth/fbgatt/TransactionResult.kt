/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt

import android.bluetooth.BluetoothGattService
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.fitbit.bluetooth.fbgatt.util.Bytes
import com.fitbit.bluetooth.fbgatt.util.GattStatus
import java.util.ArrayList
import java.util.Locale
import java.util.UUID
import kotlin.jvm.JvmField

/**
 * Data returned from a transaction. [resultStatus] and [resultState] stay fields so existing
 * Java callers can read them directly. [responseStatus] stays a private field of that name.
 */
open class TransactionResult private constructor(
    @JvmField val resultState: GattState?,
    @JvmField val resultStatus: TransactionResultStatus?,
    private val responseStatus: Int,
    private val rssi: Int,
    private val mtu: Int,
    private val requestId: Int,
    private val characteristicUuid: UUID?,
    private val serviceUuid: UUID?,
    private val descriptorUuid: UUID?,
    data: ByteArray?,
    private val offset: Int,
    services: List<BluetoothGattService>?,
    private val preparedWrite: Boolean,
    private val responseRequired: Boolean,
    transactionName: String?,
    private val txPhy: Int,
    private val rxPhy: Int,
    results: List<TransactionResult>?
) {
    enum class TransactionResultStatus {
        SUCCESS,
        FAILURE,
        INVALID_STATE,
        TIMEOUT
    }

    private var storedData: ByteArray? = data
    private var transactionName: String? = transactionName
    private var transactionResults: ArrayList<TransactionResult> =
        if (results == null) ArrayList() else ArrayList(results)
    private val gattServerServices: List<BluetoothGattService> = services ?: ArrayList()

    private constructor(result: TransactionResult, newTransactionName: String?) : this(
        result.resultState,
        result.resultStatus,
        result.responseStatus,
        result.rssi,
        result.mtu,
        result.requestId,
        result.characteristicUuid,
        result.serviceUuid,
        result.descriptorUuid,
        result.storedData,
        result.offset,
        result.gattServerServices,
        result.preparedWrite,
        result.responseRequired,
        result.transactionName,
        result.txPhy,
        result.rxPhy,
        result.transactionResults
    ) {
        this.transactionName = newTransactionName
        this.transactionResults = ArrayList()
    }

    fun setData(data: ByteArray?) {
        storedData = data
    }

    @Nullable
    fun getData(): ByteArray? = storedData

    fun getRssi(): Int = rssi

    fun getMtu(): Int = mtu

    fun getRequestId(): Int = requestId

    fun getTxPhy(): Int = txPhy

    fun getRxPhy(): Int = rxPhy

    @NonNull
    fun getResultStatus(): TransactionResultStatus = resultStatus!!

    @NonNull
    fun getServices(): List<BluetoothGattService> = gattServerServices

    @Nullable
    fun getDescriptorUuid(): UUID? = descriptorUuid

    @Nullable
    fun getCharacteristicUuid(): UUID? = characteristicUuid

    @Nullable
    fun getServiceUuid(): UUID? = serviceUuid

    fun getOffset(): Int = offset

    fun isPreparedWrite(): Boolean = preparedWrite

    fun isResponseRequired(): Boolean = responseRequired

    fun getResponseCodeString(): String = GattStatus.getStatusForCode(responseStatus).name

    fun getTransactionName(): String? = transactionName

    fun getResultState(): GattState? = resultState

    fun getTransactionResults(): List<TransactionResult> = transactionResults

    override fun toString(): String {
        return String.format(
            Locale.ENGLISH,
            "Transaction Name: %s, Gatt State: %s, Transaction Result Status: %s, Response Status: %s, rssi: %d, mtu: %d, Characteristic UUID: %s, Service UUID: %s, Descriptor UUID: %s, Data: %s, Offset: %d, txPhy: %d, rxPhy: %d, transaction results: %s",
            transactionName,
            resultState,
            resultStatus,
            GattStatus.getStatusForCode(responseStatus),
            rssi,
            mtu,
            characteristicUuid,
            serviceUuid,
            descriptorUuid,
            Bytes.byteArrayToHexString(storedData),
            offset,
            txPhy,
            rxPhy,
            transactionResults
        )
    }

    class Builder {
        private var responseStatus: Int = 0
        private var requestId: Int = 0
        private var characteristicUuid: UUID? = null
        private var serviceUuid: UUID? = null
        private var descriptorUuid: UUID? = null
        private var resultState: GattState? = null
        private var resultStatus: TransactionResultStatus? = null
        private var data: ByteArray? = null
        private var rssi: Int = 0
        private var mtu: Int = 0
        private var offset: Int = 0
        private var txPhy: Int = 1
        private var rxPhy: Int = 1
        private var services: List<BluetoothGattService>? = ArrayList()
        private var preparedWrite: Boolean = false
        private var responseRequired: Boolean = false
        private var transactionName: String? = "Unknown"
        private val results: ArrayList<TransactionResult> = ArrayList()

        fun addTransactionResults(results: List<TransactionResult>?): Builder {
            if (results != null) {
                this.results.addAll(results)
            }
            return this
        }

        fun addTransactionResult(transactionResult: TransactionResult?): Builder {
            if (transactionResult != null) {
                results.add(transactionResult)
            }
            return this
        }

        fun serverServices(services: List<BluetoothGattService>?): Builder {
            this.services = services
            return this
        }

        fun data(data: ByteArray?): Builder {
            this.data = data
            return this
        }

        fun responseStatus(code: Int): Builder {
            responseStatus = code
            return this
        }

        fun rssi(rssi: Int): Builder {
            this.rssi = rssi
            return this
        }

        fun mtu(mtu: Int): Builder {
            this.mtu = mtu
            return this
        }

        fun requestId(requestId: Int): Builder {
            this.requestId = requestId
            return this
        }

        fun characteristicUuid(characteristicUuid: UUID?): Builder {
            this.characteristicUuid = characteristicUuid
            return this
        }

        fun serviceUuid(serviceUuid: UUID?): Builder {
            this.serviceUuid = serviceUuid
            return this
        }

        fun descriptorUuid(descriptorUuid: UUID?): Builder {
            this.descriptorUuid = descriptorUuid
            return this
        }

        fun gattState(state: GattState?): Builder {
            resultState = state
            return this
        }

        fun resultStatus(resultStatus: TransactionResultStatus?): Builder {
            this.resultStatus = resultStatus
            return this
        }

        fun offset(offset: Int): Builder {
            this.offset = offset
            return this
        }

        fun preparedWrite(preparedWrite: Boolean): Builder {
            this.preparedWrite = preparedWrite
            return this
        }

        fun responseRequired(responseRequired: Boolean): Builder {
            this.responseRequired = responseRequired
            return this
        }

        fun transactionName(@Nullable transactionName: String?): Builder {
            this.transactionName = transactionName ?: "Unknown"
            return this
        }

        fun txPhy(txPhy: Int): Builder {
            this.txPhy = txPhy
            return this
        }

        fun rxPhy(rxPhy: Int): Builder {
            this.rxPhy = rxPhy
            return this
        }

        fun build(): TransactionResult {
            return TransactionResult(
                resultState,
                resultStatus,
                responseStatus,
                rssi,
                mtu,
                requestId,
                characteristicUuid,
                serviceUuid,
                descriptorUuid,
                data,
                offset,
                services,
                preparedWrite,
                responseRequired,
                transactionName,
                txPhy,
                rxPhy,
                results
            )
        }
    }
}
