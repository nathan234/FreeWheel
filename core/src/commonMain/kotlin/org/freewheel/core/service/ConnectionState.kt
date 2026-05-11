package org.freewheel.core.service

import org.freewheel.core.ble.DiscoveredServices

/**
 * Represents the current state of the Bluetooth connection to a wheel.
 */
sealed class ConnectionState {
    /**
     * Not connected and not attempting to connect.
     */
    data object Disconnected : ConnectionState()

    /**
     * Currently scanning for available wheels.
     */
    data object Scanning : ConnectionState()

    /**
     * Attempting to connect to a specific wheel.
     */
    data class Connecting(val address: String) : ConnectionState()

    /**
     * Connected and discovering BLE services.
     */
    data class DiscoveringServices(val address: String) : ConnectionState()

    /**
     * Fully connected and receiving data.
     */
    data class Connected(val address: String, val wheelName: String) : ConnectionState()

    /**
     * Connection lost, may attempt reconnection.
     */
    data class ConnectionLost(
        val address: String,
        val reason: String,
        val issue: ConnectionIssue = ConnectionIssue.unknownRecoverable(reason),
    ) : ConnectionState()

    /**
     * Connection failed with an error.
     */
    data class Failed(
        val error: String,
        val address: String? = null,
        val issue: ConnectionIssue = ConnectionIssue.unknownTerminal(error),
    ) : ConnectionState()

    /**
     * BLE is connected and services discovered, but the wheel type couldn't be
     * resolved from the topology fingerprint and no saved profile / explicit
     * hint exists. The user must pick a wheel type before we can build a
     * decoder. The peripheral is still connected — once the user confirms via
     * [WheelConnectionManager.confirmWheelType], we run [WcmEffect.ConfigureBle]
     * against the existing peripheral and transition to [Connected] without a
     * reconnect.
     */
    data class WheelTypeRequired(
        val address: String,
        val services: DiscoveredServices,
        val deviceName: String?,
    ) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val isConnecting: Boolean
        get() = this is Connecting || this is DiscoveringServices || this is WheelTypeRequired

    val isDisconnected: Boolean
        get() = this is Disconnected || this is Failed || this is ConnectionLost

    val connectingAddress: String?
        get() = when (this) {
            is Connecting -> address
            is DiscoveringServices -> address
            is WheelTypeRequired -> address
            else -> null
        }

    val failedAddress: String?
        get() = (this as? Failed)?.address

    val connectionIssue: ConnectionIssue?
        get() = when (this) {
            is ConnectionLost -> issue
            is Failed -> issue
            else -> null
        }

    val statusText: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Scanning -> "Scanning..."
            is Connecting -> "Connecting..."
            is DiscoveringServices -> "Discovering services..."
            is Connected -> "Connected to $wheelName"
            is ConnectionLost -> "Connection lost: $reason"
            is Failed -> "Failed: $error"
            is WheelTypeRequired -> "Select wheel type"
        }
}
