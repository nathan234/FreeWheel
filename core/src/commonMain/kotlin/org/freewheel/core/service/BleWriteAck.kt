package org.freewheel.core.service

/**
 * Acknowledgement that a single BLE write either completed or failed at the
 * platform layer.
 *
 * Commit 1 of the Kingsong BLE parity work (see `KINGSONG_BLE_PARITY_PLAN.md`).
 * The richer typed write-request/result contract lands in Commit 2; this type
 * is intentionally narrow so the foundation can ship without introducing a
 * new platform-write API yet.
 *
 * Semantics:
 *
 * - [success] true means the platform delivered a write-completion callback
 *   without an error (Android `GattStatus.SUCCESS` from
 *   `onCharacteristicWrite`, iOS `didWriteValueForCharacteristic` with
 *   `error == nil`).
 * - On iOS, completion callbacks fire only for `WITH_RESPONSE` writes. For
 *   the current `WITHOUT_RESPONSE` path no ack is emitted; the existing
 *   `peripheralIsReadyToSendWriteWithoutResponse` flow remains intact.
 * - [data] is a copy of the bytes the platform reported back, used by tests
 *   and BLE-capture tooling to correlate acks with outbound writes.
 * - A completion here does not mean the wheel processed the setting — that
 *   "real confirmation" layer is built on top in a later commit via decoder
 *   readback evidence.
 */
data class BleWriteAck(
    /** Session id stamped by the platform BLE layer at write-emission time. */
    val attemptId: Long,
    /** Platform reported a clean write completion. */
    val success: Boolean,
    /** Bytes the platform acknowledged. Copied — callers may retain. */
    val data: ByteArray,
    /** Platform error string when [success] is false, otherwise null. */
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleWriteAck) return false
        return attemptId == other.attemptId &&
                success == other.success &&
                data.contentEquals(other.data) &&
                error == other.error
    }

    override fun hashCode(): Int {
        var result = attemptId.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}
