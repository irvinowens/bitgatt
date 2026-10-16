/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.engine

import com.fitbit.bluetooth.fbgatt.GattState
import com.fitbit.bluetooth.fbgatt.StateType

/**
 * Whether a transaction may start from the connection's current [GattState].
 * Every [GattState] constant is named in [check] so a new constant fails compilation.
 */
object Admit {
    enum class GuardState {
        OK,
        INVALID_TARGET_STATE,
    }

    @JvmStatic
    fun check(
        currentState: GattState,
        successState: GattState,
        admitsRegardlessOfState: Boolean,
    ): GuardState {
        if (currentState == GattState.BT_OFF) {
            return GuardState.INVALID_TARGET_STATE
        }
        if (admitsRegardlessOfState) {
            return GuardState.OK
        }
        return when (currentState) {
            GattState.BT_OFF -> GuardState.INVALID_TARGET_STATE
            GattState.DISCONNECTED,
            GattState.DISCONNECTING,
            GattState.SERVER_CLOSED,
            GattState.FAILURE_DISCONNECTING,
            GattState.CONNECTING,
            GattState.FAILURE_CONNECTING,
            GattState.CONNECTED,
            GattState.IDLE,
            GattState.GATT_CONNECTION_STATE_SET_SUCCESSFULLY,
            GattState.GATT_CONNECTION_STATE_SET_FAILURE,
            GattState.GATT_CONNECTION_STATE_SET_IN_PROGRESS,
            GattState.REFRESH_GATT_SUCCESS,
            GattState.REFRESH_GATT_FAILURE,
            GattState.REFRESH_GATT_IN_PROGRESS,
            GattState.CLOSED,
            GattState.DISCOVERING,
            GattState.DISCOVERY_SUCCESS,
            GattState.DISCOVERY_FAILURE,
            GattState.CREATING_BOND,
            GattState.CREATE_BOND_FAILURE,
            GattState.CREATE_BOND_SUCCESS,
            GattState.ADDING_SERVICE,
            GattState.ADD_SERVICE_SUCCESS,
            GattState.ADD_SERVICE_FAILURE,
            GattState.ADDING_SERVICE_CHARACTERISTIC,
            GattState.ADD_SERVICE_CHARACTERISTIC_SUCCESS,
            GattState.ADD_SERVICE_CHARACTERISTIC_FAILURE,
            GattState.ADDING_SERVICE_CHARACTERISTIC_DESCRIPTOR,
            GattState.ADD_SERVICE_CHARACTERISTIC_DESCRIPTOR_SUCCESS,
            GattState.ADD_SERVICE_CHARACTERISTIC_DESCRIPTOR_FAILURE,
            GattState.WRITING_CHARACTERISTIC,
            GattState.WRITE_CHARACTERISTIC_SUCCESS,
            GattState.WRITE_CHARACTERISTIC_FAILURE,
            GattState.READING_CHARACTERISTIC,
            GattState.READ_CHARACTERISTIC_SUCCESS,
            GattState.READ_CHARACTERISTIC_FAILURE,
            GattState.WRITING_DESCRIPTOR,
            GattState.WRITE_DESCRIPTOR_SUCCESS,
            GattState.WRITE_DESCRIPTOR_FAILURE,
            GattState.READING_DESCRIPTOR,
            GattState.READ_DESCRIPTOR_SUCCESS,
            GattState.READ_DESCRIPTOR_FAILURE,
            GattState.READING_RSSI,
            GattState.READ_RSSI_SUCCESS,
            GattState.READ_RSSI_FAILURE,
            GattState.REQUESTING_MTU,
            GattState.REQUEST_MTU_SUCCESS,
            GattState.REQUEST_MTU_FAILURE,
            GattState.ENABLING_CHARACTERISTIC_NOTIFICATION,
            GattState.ENABLE_CHARACTERISTIC_NOTIFICATION_SUCCESS,
            GattState.ENABLE_CHARACTERISTIC_NOTIFICATION_FAILURE,
            GattState.DISABLING_CHARACTERISTIC_NOTIFICATION,
            GattState.DISABLE_CHARACTERISTIC_NOTIFICATION_SUCCESS,
            GattState.DISABLE_CHARACTERISTIC_NOTIFICATION_FAILURE,
            GattState.NOTIFYING_CHARACTERISTIC,
            GattState.NOTIFY_CHARACTERISTIC_SUCCESS,
            GattState.NOTIFY_CHARACTERISTIC_FAILURE,
            GattState.INDICATING_CHARACTERISTIC,
            GattState.INDICATE_CHARACTERISTIC_SUCCESS,
            GattState.INDICATE_CHARACTERISTIC_FAILURE,
            GattState.REQUESTING_CONNECTION_INTERVAL_CHANGE,
            GattState.REQUEST_CONNECTION_INTERVAL_SUCCESS,
            GattState.REQUEST_CONNECTION_INTERVAL_FAILURE,
            GattState.CLOSING_GATT_CLIENT,
            GattState.CLOSE_GATT_CLIENT_SUCCESS,
            GattState.SENDING_SERVER_RESPONSE,
            GattState.SEND_SERVER_RESPONSE_FAILURE,
            GattState.SEND_SERVER_RESPONSE_SUCCESS,
            GattState.GETTING_SERVER_SERVICES,
            GattState.GET_SERVER_SERVICES_SUCCESS,
            GattState.CLEARING_GATT_SERVER_SERVICES,
            GattState.CLEAR_GATT_SERVER_SERVICES_SUCCESS,
            GattState.CLOSING_GATT_SERVER,
            GattState.CLOSE_GATT_SERVER_SUCCESS,
            GattState.CLOSE_GATT_SERVER_FAILURE,
            GattState.REMOVING_SERVER_SERVICE,
            GattState.REMOVE_SERVER_SERVICE_FAILURE,
            GattState.REMOVE_SERVER_SERVICE_SUCCESS,
            GattState.REQUESTING_PHY_CHANGE,
            GattState.REQUEST_PHY_CHANGE_SUCCESS,
            GattState.REQUEST_PHY_CHANGE_FAILURE,
            GattState.READING_CURRENT_PHY,
            GattState.READ_CURRENT_PHY_SUCCESS,
            GattState.READ_CURRENT_PHY_FAILURE,
            -> rules(currentState, successState)
        }
    }

    private fun rules(currentState: GattState, successState: GattState): GuardState {
        if (successState == GattState.CONNECTED &&
            currentState != GattState.DISCONNECTED &&
            currentState != GattState.CONNECTING
        ) {
            return GuardState.INVALID_TARGET_STATE
        }
        if (successState == GattState.DISCONNECTED &&
            currentState != GattState.IDLE &&
            currentState != GattState.CONNECTED
        ) {
            return GuardState.INVALID_TARGET_STATE
        }
        if ((currentState == GattState.DISCONNECTED || currentState == GattState.DISCONNECTING) &&
            successState != GattState.CONNECTED
        ) {
            return GuardState.INVALID_TARGET_STATE
        }
        val currentType = currentState.getStateType()
        val successType = successState.getStateType()
        if ((currentType == StateType.IDLE || currentType == StateType.IN_PROGRESS) &&
            successType == StateType.IDLE
        ) {
            return GuardState.OK
        }
        return GuardState.INVALID_TARGET_STATE
    }
}
