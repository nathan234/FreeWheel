package org.freewheel.core.logging

import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.withLock

/**
 * A single unique unhandled frame, with occurrence tracking.
 */
data class UnhandledFrameEntry(
    val frameHex: String,
    val frameSize: Int,
    val reason: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int
)

/**
 * Collects unique unhandled BLE frames during a session.
 *
 * Deduplicates by frame hex content. Thread-safe via [Lock].
 * Capped at [MAX_ENTRIES] to prevent unbounded memory growth.
 */
class UnhandledFrameCollector {

    private val lock = Lock()
    private val entries = LinkedHashMap<String, UnhandledFrameEntry>()

    /**
     * Record an unhandled frame. Deduplicates by frame content.
     */
    fun record(reason: String, frameData: ByteArray, currentTimeMs: Long) {
        lock.withLock {
            val hex = ByteUtils.bytesToHex(frameData)
            val existing = entries[hex]
            if (existing != null) {
                entries[hex] = existing.copy(
                    lastSeenMs = currentTimeMs,
                    count = existing.count + 1
                )
            } else if (entries.size < MAX_ENTRIES) {
                entries[hex] = UnhandledFrameEntry(
                    frameHex = hex,
                    frameSize = frameData.size,
                    reason = reason,
                    firstSeenMs = currentTimeMs,
                    lastSeenMs = currentTimeMs,
                    count = 1
                )
            }
        }
    }

    /**
     * Snapshot of all entries, ordered by first-seen time.
     */
    fun getEntries(): List<UnhandledFrameEntry> = lock.withLock {
        entries.values.toList()
    }

    /**
     * Number of unique unhandled frames recorded.
     */
    fun count(): Int = lock.withLock { entries.size }

    /**
     * Total occurrences across all unique frames.
     */
    fun totalOccurrences(): Int = lock.withLock {
        entries.values.sumOf { it.count }
    }

    /**
     * Clear all recorded frames. Call on new session (connect).
     */
    fun clear() {
        lock.withLock { entries.clear() }
    }

    companion object {
        internal const val MAX_ENTRIES = 200
    }
}
