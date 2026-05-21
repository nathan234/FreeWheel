package org.freewheel.core.service

/**
 * Wheel-specific BLE transport behavior. Attached to [org.freewheel.core.ble.WheelConnectionInfo]
 * so wheel-family choices (UUIDs) and transport choices (write mode, pacing,
 * MTU, warmup, heartbeat) travel together through service discovery and into
 * the write path.
 *
 * Commit 2 of the Kingsong BLE parity plan introduces the type and pipes it
 * through the manager + platform layers. Every wheel used
 * [WheelTransportProfile.Default] up through Commit 2. Commit 3 lands
 * [KingsongClassic], the first non-default profile, by wiring
 * [org.freewheel.core.ble.WheelConnectionInfo.forKingsong] to it; every other
 * factory still uses [Default]. KSE is deferred to Commit 4.
 *
 * @property writeType Which BLE write mode the [WriteCoordinator] should
 *   request when emitting a packet. Defaults to WITHOUT_RESPONSE — the only
 *   mode used today.
 * @property requestMaxMtu Whether the platform layer should request the
 *   maximum negotiated MTU on connect. Defaults to true to match today's
 *   unconditional behavior. Wired through the manager in Commit 2; the
 *   first behaviorally meaningful non-default value lands with KSE in
 *   Commit 4.
 * @property interWriteSpacingMs Minimum delay between consecutive writes the
 *   coordinator should enforce. 0 disables spacing. Default (0) matches the
 *   pre-Commit-2 fire-and-forget cadence.
 * @property retryPolicy How aggressively the coordinator should retry failed
 *   writes for this profile. Default is no retries — same as today.
 * @property keepAlivePolicy Whether keepalive comes from the decoder, the
 *   transport (a fixed frame), or is disabled. Default delegates to the
 *   decoder (current behavior for every wheel). [KingsongClassic] uses
 *   [TransportKeepAlivePolicy.FixedFrame] to replace the decoder-driven path
 *   entirely.
 * @property postConnectWarmups Transport-driven traffic to emit shortly after
 *   the BLE-ready signal. [KingsongClassic] populates this with the one-shot
 *   `0x5E` warmup; every other profile leaves it empty.
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
         * behavior. Every non-Kingsong wheel still uses this; Commit 3 only
         * promotes classic Kingsong onto [KingsongClassic].
         */
        val Default: WheelTransportProfile = WheelTransportProfile()

        /**
         * Transport profile for classic Kingsong wheels (KS-S16 / S18 /
         * S20 / S22 family — every wheel that uses the `FFE0` service).
         * KSE (KS-E1/E3 with `AD00` service) gets a distinct profile in
         * Commit 4.
         *
         * Pinned values reflect what the official Kingsong DLC Android app
         * does after notify-ready:
         * - WITHOUT_RESPONSE writes (classic firmware does not require peer ack)
         * - request max MTU on connect
         * - 50ms inter-write spacing so closely-spaced settings writes
         *   don't outrun the wheel's notification cadence
         * - one retry with a 50ms backoff (mirrors the official app's
         *   forgiving send loop)
         * - replace the decoder keepalive with the transport-driven blank
         *   heartbeat every 1000ms
         * - one-shot `0x5E` warmup 2500ms after BLE-ready
         *
         * Provenance for the exact frame bytes lives on
         * [postConnectWarmups] and the [TransportKeepAlivePolicy.FixedFrame]
         * literal below.
         */
        val KingsongClassic: WheelTransportProfile = WheelTransportProfile(
            writeType = BleWriteType.WITHOUT_RESPONSE,
            requestMaxMtu = true,
            interWriteSpacingMs = 50,
            retryPolicy = RetryPolicy(maxRetries = 1, retryBackoffMs = 50),
            keepAlivePolicy = TransportKeepAlivePolicy.FixedFrame(
                intervalMs = 1000,
                // Recurring blank Kingsong heartbeat sent every second once
                // notifications are active. Provenance:
                //   freewheel-data/euc-reference-apps/kingsong/jadx_out/
                //   sources/com/kingsong/dlc/service/BleService.java:797
                //   sources/p000/C6720gh.java:512
                frame = byteArrayOf(
                    0xAA.toByte(), 0x55.toByte(),
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00,
                    0x14, 0x5A, 0x5A,
                ),
                annotation = "ks-heartbeat",
            ),
            postConnectWarmups = listOf(
                PostConnectWarmup(
                    delayMs = 2500,
                    // One-shot 0x5E warmup posted 2.5s after notify success.
                    // Byte 16 = 0x5E. Provenance:
                    //   freewheel-data/euc-reference-apps/kingsong/jadx_out/
                    //   sources/com/kingsong/dlc/service/BleService.java:347
                    //   sources/com/kingsong/dlc/service/BleService.java:400
                    //   sources/com/kingsong/dlc/service/BleService.java:404
                    frame = byteArrayOf(
                        0xAA.toByte(), 0x55.toByte(),
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00,
                        0x5E,
                        0x14, 0x5A, 0x5A,
                    ),
                    annotation = "ks-0x5e-warmup",
                ),
            ),
        )

        /**
         * Transport profile for KS-E1 / KS-E3 ("KSE") wheels.
         *
         * Commit 4 of the Kingsong BLE parity plan. Intentionally conservative:
         * the official Kingsong DLC Android app's KSE path does not request
         * MTU, does not pace writes, and we have no capture proving the
         * classic `0x5E` warmup or 1 Hz blank heartbeat applies to KSE
         * firmware. Until a real-hardware capture says otherwise, KSE
         * runs a vanilla WITHOUT_RESPONSE transport with no transport-driven
         * maintenance — strictly safer than borrowing classic assumptions.
         *
         * The decoder is unchanged (`KingsongDecoder`); the variant lives
         * purely on the transport surface (UUIDs + this profile).
         */
        val KingsongKse: WheelTransportProfile = WheelTransportProfile(
            writeType = BleWriteType.WITHOUT_RESPONSE,
            requestMaxMtu = false,
            interWriteSpacingMs = 0,
            retryPolicy = RetryPolicy(),
            keepAlivePolicy = TransportKeepAlivePolicy.None,
            postConnectWarmups = emptyList(),
        )
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
 *   [BleWriteResult.Completed]. No wheel opts in yet (Commit 3 keeps Kingsong
 *   on WITHOUT_RESPONSE to match the official app); the path exists so a
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
 * Commit 2 reserved the type; Commit 3 wires the WCM effect executor to act
 * on [None] and [FixedFrame] (the classic Kingsong heartbeat is the first
 * non-[UseDecoder] consumer).
 */
sealed class TransportKeepAlivePolicy {
    /** Keepalive is decoder-driven (every non-Kingsong wheel). */
    data object UseDecoder : TransportKeepAlivePolicy()

    /**
     * No keepalive should run for this transport. The decoder-driven path
     * is suppressed by [WheelConnectionManager.setupDecoderTransition].
     */
    data object None : TransportKeepAlivePolicy()

    /**
     * Transport-driven keepalive: emit [frame] every [intervalMs] starting
     * once the BLE-ready signal fires. When this policy is in effect, the
     * decoder-driven keepalive is suppressed entirely so the two paths can
     * never run in parallel.
     *
     * @property annotation Tag forwarded to BLE-capture tooling and
     *   [BleWriteRequest.annotation] so transport-driven heartbeats can be
     *   filtered apart from semantic command writes (e.g. `"ks-heartbeat"`).
     */
    data class FixedFrame(
        val intervalMs: Long,
        val frame: ByteArray,
        val annotation: String = "",
    ) : TransportKeepAlivePolicy() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FixedFrame) return false
            return intervalMs == other.intervalMs &&
                frame.contentEquals(other.frame) &&
                annotation == other.annotation
        }

        override fun hashCode(): Int {
            var result = intervalMs.hashCode()
            result = 31 * result + frame.contentHashCode()
            result = 31 * result + annotation.hashCode()
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
