package org.freewheel.core.domain

/**
 * A single event log entry from the wheel's internal error/event history.
 *
 * Veteran/Leaperkim wheels store up to 255 log entries with error codes,
 * timestamps, and optional diagnostic data. Downloaded via sub-types 0/4
 * (basic), 32 (extended), or 33 (full detail with text).
 *
 * @param index Sequential entry index (0-254)
 * @param totalCount Total number of log entries on the wheel (-1 if unknown)
 * @param contentCode Error/event code (2-byte value)
 * @param timestamp Unix timestamp in seconds (0 if unavailable)
 * @param extras Diagnostic values (signed 32-bit ints, variable count)
 * @param text GBK-decoded description string (empty if unavailable)
 * @param extraBytes Raw extra bytes from sub-type 32 (5 bytes per entry)
 */
data class EventLogEntry(
    val index: Int,
    val totalCount: Int = -1,
    val contentCode: Int,
    val timestamp: Long = 0,
    val extras: List<Long> = emptyList(),
    val text: String = "",
    val extraBytes: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventLogEntry) return false
        return index == other.index && totalCount == other.totalCount &&
            contentCode == other.contentCode && timestamp == other.timestamp &&
            extras == other.extras && text == other.text &&
            extraBytes.contentEquals(other.extraBytes)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + totalCount
        result = 31 * result + contentCode
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + extras.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + extraBytes.contentHashCode()
        return result
    }
}
