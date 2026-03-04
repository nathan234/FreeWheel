package org.freewheel.core.service

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
    data class ConnectionLost(val address: String, val reason: String) : ConnectionState()

    /**
     * Connection failed with an error.
     */
    data class Failed(val error: String, val address: String? = null) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val isConnecting: Boolean
        get() = this is Connecting || this is DiscoveringServices

    val isDisconnected: Boolean
        get() = this is Disconnected || this is Failed || this is ConnectionLost

    val connectingAddress: String?
        get() = when (this) {
            is Connecting -> address
            is DiscoveringServices -> address
            else -> null
        }

    val failedAddress: String?
        get() = (this as? Failed)?.address

    val statusText: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Scanning -> "Scanning..."
            is Connecting -> "Connecting..."
            is DiscoveringServices -> "Discovering services..."
            is Connected -> "Connected to $wheelName"
            is ConnectionLost -> "Connection lost: $reason"
            is Failed -> "Failed: $error"
        }
}
