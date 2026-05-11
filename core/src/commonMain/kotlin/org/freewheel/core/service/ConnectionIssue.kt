package org.freewheel.core.service

/**
 * Structured cause metadata for BLE disconnect and failure states.
 *
 * The user-facing message remains on [ConnectionState.ConnectionLost.reason]
 * / [ConnectionState.Failed.error] for UI compatibility, while this type gives
 * reconnect policy and ride-recovery code a stable classification that does
 * not depend on parsing free-form strings.
 */
enum class ConnectionIssueCode {
    UNKNOWN,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_POWERED_OFF,
    BLUETOOTH_STATE_CHANGED,
    PERIPHERAL_NOT_FOUND,
    CONNECTION_TIMED_OUT,
    CONNECTION_FAILED,
    PERIPHERAL_DISCONNECTED,
    INVALID_HANDLE,
    PEER_REMOVED_PAIRING,
    ENCRYPTION_TIMED_OUT,
    PREVIOUS_CONNECTION_STILL_DRAINING,
    SERVICE_DISCOVERY_TIMED_OUT,
    SERVICE_DISCOVERY_FAILED,
    NO_SUPPORTED_SERVICES,
    REQUIRED_CHARACTERISTIC_MISSING,
}

enum class RecoveryDisposition {
    RECOVERABLE,
    TERMINAL,
}

data class ConnectionIssue(
    val code: ConnectionIssueCode,
    val message: String,
    val recoveryDisposition: RecoveryDisposition,
) {
    val isRecoverable: Boolean
        get() = recoveryDisposition == RecoveryDisposition.RECOVERABLE

    companion object {
        fun recoverable(
            code: ConnectionIssueCode,
            message: String,
        ): ConnectionIssue = ConnectionIssue(
            code = code,
            message = message,
            recoveryDisposition = RecoveryDisposition.RECOVERABLE,
        )

        fun terminal(
            code: ConnectionIssueCode,
            message: String,
        ): ConnectionIssue = ConnectionIssue(
            code = code,
            message = message,
            recoveryDisposition = RecoveryDisposition.TERMINAL,
        )

        fun unknownRecoverable(message: String): ConnectionIssue =
            recoverable(ConnectionIssueCode.UNKNOWN, message)

        fun unknownTerminal(message: String): ConnectionIssue =
            terminal(ConnectionIssueCode.UNKNOWN, message)
    }
}
