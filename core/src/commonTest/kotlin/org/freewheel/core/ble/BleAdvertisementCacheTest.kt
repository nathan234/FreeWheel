package org.freewheel.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BleAdvertisementCacheTest {

    private var clock: Long = 1_000L
    private fun now() = clock

    private fun cache(maxSize: Int = 4, ttlMs: Long = 60_000L) =
        BleAdvertisementCache(maxSize = maxSize, ttlMs = ttlMs, nowMs = ::now)

    private fun ad(addr: String, lastSeen: Long = clock): BleAdvertisement =
        BleAdvertisement(
            address = addr,
            advertisedName = null,
            peripheralName = null,
            rssi = -50,
            advertisedServiceUuids = emptySet(),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            connectable = true,
            lastSeenMs = lastSeen,
        )

    @Test
    fun `miss returns null`() {
        assertNull(cache().get("AA:BB"))
    }

    @Test
    fun `put then get returns advertisement`() {
        val c = cache()
        val a = ad("AA:BB")
        c.put(a)
        assertEquals(a, c.get("AA:BB"))
    }

    @Test
    fun `expired entry is removed on read and returns null`() {
        val c = cache(ttlMs = 60_000L)
        c.put(ad("AA:BB", lastSeen = 1_000L))
        clock = 1_000L + 60_001L
        assertNull(c.get("AA:BB"))
        // Subsequent read also null — entry is gone, not just temporarily stale.
        clock = 1_000L
        assertNull(c.get("AA:BB"))
    }

    @Test
    fun `entry exactly at TTL boundary is still served`() {
        val c = cache(ttlMs = 60_000L)
        c.put(ad("AA:BB", lastSeen = 1_000L))
        clock = 1_000L + 60_000L
        assertNotNull(c.get("AA:BB"))
    }

    @Test
    fun `LRU evicts oldest at capacity`() {
        val c = cache(maxSize = 3)
        c.put(ad("A"))
        c.put(ad("B"))
        c.put(ad("C"))
        c.put(ad("D")) // evicts A
        assertNull(c.get("A"))
        assertNotNull(c.get("B"))
        assertNotNull(c.get("C"))
        assertNotNull(c.get("D"))
    }

    @Test
    fun `get promotes entry to most-recent`() {
        val c = cache(maxSize = 3)
        c.put(ad("A"))
        c.put(ad("B"))
        c.put(ad("C"))
        // Touch A — without access-order LRU it would be the next eviction.
        assertNotNull(c.get("A"))
        c.put(ad("D")) // evicts B, not A
        assertNull(c.get("B"))
        assertNotNull(c.get("A"))
    }

    @Test
    fun `re-put resets lastSeenMs and promotes`() {
        val c = cache(maxSize = 2, ttlMs = 60_000L)
        c.put(ad("A", lastSeen = 1_000L))
        c.put(ad("B", lastSeen = 1_000L))

        clock = 1_000L + 30_000L
        c.put(ad("A", lastSeen = clock)) // refresh A; B is now older

        clock = 1_000L + 60_500L // past original TTL but within A's refreshed TTL
        assertNotNull(c.get("A"))
        assertNull(c.get("B"))
    }

    @Test
    fun `expired entries are evicted on insert`() {
        val c = cache(maxSize = 4, ttlMs = 60_000L)
        c.put(ad("A", lastSeen = 1_000L))
        c.put(ad("B", lastSeen = 1_000L))
        clock = 1_000L + 60_001L
        c.put(ad("C", lastSeen = clock))
        // A and B expired and were evicted; C remains.
        assertEquals(1, c.size())
        assertNotNull(c.get("C"))
    }
}
