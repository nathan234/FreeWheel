package org.freewheel.core.ble

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.currentTimeMillis
import org.freewheel.core.utils.withLock

/**
 * LRU + TTL cache of [BleAdvertisement] keyed by device address.
 *
 * Both Android and iOS BleManager populate this from their scan callbacks; the
 * reducer-side [org.freewheel.core.service.WheelConnectionManager.connect] flow
 * consumes it via [BleManagerPort.getAdvertisement].
 *
 * - **Access-order LRU**: every successful [get] re-promotes the entry to the
 *   tail. Plain [LinkedHashMap] iteration order is insertion-order, so we
 *   manually remove + reinsert on read AND on write.
 * - **TTL on read**: an entry past [ttlMs] is removed and [get] returns null.
 *   This guards against acting on minutes-old evidence after a long pause.
 * - **NOT cleared on disconnect**: scan→connectA→disconnect→connectB from the
 *   same scan list is a real flow; discarding evidence breaks topology hints.
 */
class BleAdvertisementCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    private val lock = Lock()
    private val entries = LinkedHashMap<String, BleAdvertisement>()

    fun put(advertisement: BleAdvertisement): Unit = lock.withLock {
        // Remove any existing entry — re-insertion below places it at the tail.
        entries.remove(advertisement.address)
        evictExpired()
        while (entries.size >= maxSize) {
            val oldest = entries.keys.iterator().next()
            entries.remove(oldest)
        }
        entries[advertisement.address] = advertisement
    }

    fun get(address: String): BleAdvertisement? = lock.withLock {
        val entry = entries.remove(address) ?: return null
        if (nowMs() - entry.lastSeenMs > ttlMs) {
            return null
        }
        entries[address] = entry
        entry
    }

    fun size(): Int = lock.withLock { entries.size }

    fun clear(): Unit = lock.withLock { entries.clear() }

    private fun evictExpired() {
        val now = nowMs()
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value.lastSeenMs > ttlMs) {
                iter.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 64
        const val DEFAULT_TTL_MS: Long = 5L * 60L * 1000L
    }
}
