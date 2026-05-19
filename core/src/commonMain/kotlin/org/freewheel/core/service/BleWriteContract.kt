package org.freewheel.core.service

/**
 * A single outbound BLE write request. Carries the bytes plus the transport-
 * level choices ([writeType]) the platform layer needs to honor.
 *
 * Built by [WriteCoordinator] from a [WheelTransportProfile]; the manager
 * layer never constructs these directly.
 *
 * @property data Bytes to write to the bound write characteristic. A copy is
 *   surfaced back in the platform ack so callers can correlate writes.
 * @property writeType BLE write mode — WITHOUT_RESPONSE is submission-only,
 *   WITH_RESPONSE blocks for the platform completion callback.
 * @property annotation Optional tag used by BLE-capture tooling to filter
 *   transport-generated traffic (warmups, heartbeats) from semantic command
 *   writes. Defaults to empty for command writes.
 */
data class BleWriteRequest(
    val data: ByteArray,
    val writeType: BleWriteType,
    val annotation: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleWriteRequest) return false
        return data.contentEquals(other.data) &&
            writeType == other.writeType &&
            annotation == other.annotation
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + writeType.hashCode()
        result = 31 * result + annotation.hashCode()
        return result
    }
}

/**
 * Outcome of a single platform write attempt.
 *
 * Distinct from [BleWriteAck] (Commit 1) — the ack is the raw platform
 * callback; this is the typed value the platform layer returns to the
 * coordinator after possibly waiting for that callback.
 *
 * - [Submitted] — WITHOUT_RESPONSE write: the OS accepted the bytes. The
 *   peer characteristic may or may not have delivered them; that distinction
 *   is intentionally invisible here (matches today's behavior).
 * - [Completed] — WITH_RESPONSE write: the platform layer received a clean
 *   write-completion callback from the BLE stack. The [ack] surfaces the
 *   raw Commit-1 ack for tooling.
 * - [Failed] — Either submission failed (peripheral not connected, no
 *   characteristic bound) or a WITH_RESPONSE completion came back with an
 *   error. The [reason] is a short, log-safe description.
 *
 * [latencyMs] is the wall-clock time from request to outcome. Useful for
 * regression tests and future telemetry; populated by the platform layer.
 */
sealed class BleWriteResult {
    abstract val latencyMs: Long

    data class Submitted(override val latencyMs: Long) : BleWriteResult()

    data class Completed(
        val ack: BleWriteAck,
        override val latencyMs: Long,
    ) : BleWriteResult()

    data class Failed(
        val reason: String,
        override val latencyMs: Long,
    ) : BleWriteResult()
}
