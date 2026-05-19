package org.freewheel.core.service

/**
 * Wheel-specific BLE transport behavior. Attached to [org.freewheel.core.ble.WheelConnectionInfo]
 * so wheel-family choices (UUIDs) and transport choices (write mode, pacing,
 * MTU, warmup, heartbeat) travel together through service discovery and into
 * the write path.
 *
 * Commit 2 of the Kingsong BLE parity plan only introduces the type and pipes
 * it through the manager + platform layers. Every wheel still uses
 * [WheelTransportProfile.Default], whose semantics are deliberately
 * byte-equivalent to pre-Commit-2 behavior. Non-default profiles are introduced
 * in later commits (classic Kingsong pacing in Commit 3, KSE in Commit 4).
 *
 * @property writeType Which BLE write mode the [WriteCoordinator] should
 *   request when emitting a packet. Defaults to WITHOUT_RESPONSE — the only
 *   mode used today.
 * @property requestMaxMtu Whether the platform layer should request the
 *   maximum negotiated MTU on connect. Defaults to true to match today's
 *   unconditional behavior. Wired through the manager in Commit 2; the
 *   first behaviorally meaningful non-default value lands with KSE.
 * @property interWriteSpacingMs Minimum delay between consecutive writes the
 *   coordinator should enforce. 0 disables spacing. Default (0) matches the
 *   pre-Commit-2 fire-and-forget cadence.
 * @property retryPolicy How aggressively the coordinator should retry failed
 *   writes for this profile. Default is no retries — same as today.
 * @property keepAlivePolicy Whether keepalive comes from the decoder, the
 *   transport (a fixed frame), or is disabled. Default delegates to the
 *   decoder (current behavior for every wheel). Reserved field — Commit 2
 *   only stores it; Commit 3 will be the first commit that consumes it.
 * @property postConnectWarmups Transport-driven traffic to emit shortly after
 *   the BLE-ready signal. Reserved field — Commit 2 only stores it; Commit 3
 *   wires the classic Kingsong `0x5E` warmup off this field.
 */
data class WheelTransportProfile(
    val writeType: BleWriteType = BleWriteType.WITHOUT_RESPONSE,
    val requestMaxMtu: Boolean = true,
    val interWriteSpacingMs: Long = 0,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val keepAlivePolicy: TransportKeepAlivePolicy = TransportKeepAlivePolicy.UseDecoder,
    val postConnectWarmups: List<PostConnectWarmup> = emptyList(),
) {
    companion object {
        /**
         * Default transport profile — byte-equivalent to pre-Commit-2
         * behavior. Every existing wheel still uses this; later commits
         * introduce specialized profiles for classic Kingsong and KSE.
         */
        val Default: WheelTransportProfile = WheelTransportProfile()
    }
}

/**
 * How the platform layer should issue a single BLE write.
 *
 * - [WITHOUT_RESPONSE] — the only mode used by every wheel today.
 *   Submission-only: success means the OS accepted the bytes for transmission,
 *   not that the peer characteristic delivered them.
 * - [WITH_RESPONSE] — peer must ack the write. The platform layer suspends
 *   until the Commit-1 write-completion callback fires and surfaces it as
 *   [BleWriteResult.Completed]. No wheel opts in yet; the path exists so a
 *   future commit can request peer-acknowledged writes for specific commands
 *   without further platform changes.
 */
enum class BleWriteType {
    WITHOUT_RESPONSE,
    WITH_RESPONSE,
}

/**
 * Per-profile retry policy honored by [WriteCoordinator].
 *
 * @property maxRetries Number of additional attempts after the first failure.
 *   0 means "no retries" — today's behavior for every wheel.
 * @property retryBackoffMs Delay before each retry. Ignored when [maxRetries]
 *   is 0.
 */
data class RetryPolicy(
    val maxRetries: Int = 0,
    val retryBackoffMs: Long = 0,
)

/**
 * Where keepalive traffic comes from for this transport profile.
 *
 * Commit 2 reserves the type; execution wiring stays in the decoder path
 * exactly as before. Commit 3 will be the first commit to act on the
 * non-[UseDecoder] variants.
 */
sealed class TransportKeepAlivePolicy {
    /** Keepalive is decoder-driven (every existing wheel). */
    data object UseDecoder : TransportKeepAlivePolicy()

    /** No keepalive should run for this transport. */
    data object None : TransportKeepAlivePolicy()

    /**
     * Transport-driven keepalive: emit [frame] every [intervalMs] starting
     * once the BLE-ready signal fires.
     */
    data class FixedFrame(
        val intervalMs: Long,
        val frame: ByteArray,
    ) : TransportKeepAlivePolicy() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FixedFrame) return false
            return intervalMs == other.intervalMs && frame.contentEquals(other.frame)
        }

        override fun hashCode(): Int {
            var result = intervalMs.hashCode()
            result = 31 * result + frame.contentHashCode()
            return result
        }
    }
}

/**
 * Transport-driven traffic to emit shortly after BLE-ready.
 *
 * Commit 2 reserves the type; nothing actually fires these yet. The classic
 * Kingsong `0x5E` warmup wires off this field in Commit 3.
 *
 * @property delayMs Delay from BLE-ready to emission.
 * @property frame Raw bytes to send.
 * @property annotation Tag used by BLE-capture tooling to filter
 *   transport-generated traffic from semantic command writes.
 */
data class PostConnectWarmup(
    val delayMs: Long,
    val frame: ByteArray,
    val annotation: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PostConnectWarmup) return false
        return delayMs == other.delayMs &&
            frame.contentEquals(other.frame) &&
            annotation == other.annotation
    }

    override fun hashCode(): Int {
        var result = delayMs.hashCode()
        result = 31 * result + frame.contentHashCode()
        result = 31 * result + annotation.hashCode()
        return result
    }
}
