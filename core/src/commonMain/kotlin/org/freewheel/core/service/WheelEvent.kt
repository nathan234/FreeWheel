package org.freewheel.core.service

import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.WheelCommand

/**
 * Events processed by the [WheelConnectionManager] event loop.
 *
 * All state mutations are serialized through a single coroutine that
 * consumes these events from a Channel, eliminating race conditions
 * between concurrent callers.
 */
sealed class WheelEvent {

    // --- Connection lifecycle ---

    /** Request to connect to a wheel. */
    class ConnectRequested(
        val address: String,
        val wheelType: WheelType?
    ) : WheelEvent()

    /** Request to disconnect from the current wheel. */
    data object DisconnectRequested : WheelEvent()

    // --- BLE callbacks ---

    /**
     * Result of a BLE connect attempt.
     */
    class BleConnectResult(
        val success: Boolean,
        val address: String,
        val error: String? = null
    ) : WheelEvent()

    /**
     * BLE services have been discovered after connection.
     */
    class ServicesDiscovered(
        val services: DiscoveredServices,
        val deviceName: String?
    ) : WheelEvent()

    /**
     * The platform BLE layer reported that it could not bind the required
     * characteristics for the configured wheel type (e.g., missing service
     * or read characteristic). Fail-fast so the user sees an explicit error
     * instead of an indefinite "Discovering Services" wait.
     */
    class BleConfigureFailed(
        val address: String,
        val error: String
    ) : WheelEvent()

    /**
     * Wheel type has been determined (from auto-detect or explicit hint).
     */
    class WheelTypeDetected(
        val wheelType: WheelType
    ) : WheelEvent()

    /**
     * Raw data received from the wheel via BLE notification.
     * Fires at BLE frequency (20-100ms). Channel overhead is negligible.
     */
    class DataReceived(
        val data: ByteArray
    ) : WheelEvent()

    /**
     * BLE characteristic update failed (GATT error on Android, NSError on iOS).
     * Logged for diagnostics but does not trigger disconnection — only the OS
     * can declare the BLE link dead (via [BleDisconnected]).
     */
    data object BleError : WheelEvent()

    /**
     * The OS BLE stack reported an unexpected disconnection.
     * This is the ONLY path (besides user-initiated [DisconnectRequested]) that
     * transitions to [ConnectionState.ConnectionLost]. The OS will auto-reconnect;
     * when it does, [ServicesDiscovered] fires and the session resumes.
     */
    class BleDisconnected(
        val address: String,
        val reason: String
    ) : WheelEvent()

    // --- Timers ---

    /**
     * Keep-alive timer tick. Triggers sending a keep-alive command.
     */
    data object KeepAliveTick : WheelEvent()

    /**
     * Data timeout — no data received within the threshold.
     */
    class DataTimeout(
        val address: String
    ) : WheelEvent()

    // --- Commands ---

    /**
     * Send a command to the wheel.
     */
    class SendCommand(
        val command: WheelCommand
    ) : WheelEvent()

    // --- Event log ---

    /** Clear accumulated event log entries (before re-download). */
    data object ClearEventLog : WheelEvent()

    // --- Config ---

    /**
     * Decoder configuration has been updated (e.g., user changed units).
     */
    class ConfigUpdated(
        val config: DecoderConfig
    ) : WheelEvent()
}
