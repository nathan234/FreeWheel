package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnhandledFrameCollectorTest {

    @Test
    fun `record adds new entry with count 1`() {
        val collector = UnhandledFrameCollector()
        collector.record("unknown frame type", byteArrayOf(0x01, 0x02, 0x03), 1000L)

        assertEquals(1, collector.count())
        val entries = collector.getEntries()
        assertEquals(1, entries.size)
        assertEquals("010203", entries[0].frameHex.lowercase())
        assertEquals(3, entries[0].frameSize)
        assertEquals("unknown frame type", entries[0].reason)
        assertEquals(1000L, entries[0].firstSeenMs)
        assertEquals(1000L, entries[0].lastSeenMs)
        assertEquals(1, entries[0].count)
    }

    @Test
    fun `duplicate frame increments count and updates lastSeenMs`() {
        val collector = UnhandledFrameCollector()
        collector.record("reason", byteArrayOf(0x0A, 0x0B), 1000L)
        collector.record("reason", byteArrayOf(0x0A, 0x0B), 2000L)
        collector.record("reason", byteArrayOf(0x0A, 0x0B), 3000L)

        assertEquals(1, collector.count())
        val entry = collector.getEntries()[0]
        assertEquals(3, entry.count)
        assertEquals(1000L, entry.firstSeenMs)
        assertEquals(3000L, entry.lastSeenMs)
    }

    @Test
    fun `different frame content creates separate entries`() {
        val collector = UnhandledFrameCollector()
        collector.record("reason A", byteArrayOf(0x01), 1000L)
        collector.record("reason B", byteArrayOf(0x02), 2000L)

        assertEquals(2, collector.count())
        assertEquals(2, collector.getEntries().size)
    }

    @Test
    fun `totalOccurrences sums across entries`() {
        val collector = UnhandledFrameCollector()
        collector.record("r", byteArrayOf(0x01), 1000L)
        collector.record("r", byteArrayOf(0x01), 2000L) // count = 2
        collector.record("r", byteArrayOf(0x02), 3000L) // count = 1

        assertEquals(3, collector.totalOccurrences())
    }

    @Test
    fun `clear removes all entries`() {
        val collector = UnhandledFrameCollector()
        collector.record("r", byteArrayOf(0x01), 1000L)
        collector.record("r", byteArrayOf(0x02), 2000L)

        collector.clear()
        assertEquals(0, collector.count())
        assertTrue(collector.getEntries().isEmpty())
    }

    @Test
    fun `getEntries returns a snapshot in insertion order`() {
        val collector = UnhandledFrameCollector()
        collector.record("first", byteArrayOf(0xAA.toByte()), 1000L)
        collector.record("second", byteArrayOf(0xBB.toByte()), 2000L)
        collector.record("third", byteArrayOf(0xCC.toByte()), 3000L)

        val entries = collector.getEntries()
        assertEquals("first", entries[0].reason)
        assertEquals("second", entries[1].reason)
        assertEquals("third", entries[2].reason)
    }

    @Test
    fun `capacity is capped at MAX_ENTRIES`() {
        val collector = UnhandledFrameCollector()
        // Fill to capacity
        for (i in 0 until UnhandledFrameCollector.MAX_ENTRIES) {
            collector.record("r", byteArrayOf(i.toByte()), 1000L + i)
        }
        assertEquals(UnhandledFrameCollector.MAX_ENTRIES, collector.count())

        // One more should be dropped
        collector.record("overflow", byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 9999L)
        assertEquals(UnhandledFrameCollector.MAX_ENTRIES, collector.count())
    }

    @Test
    fun `existing entries can still be incremented beyond capacity`() {
        val collector = UnhandledFrameCollector()
        // Fill to capacity
        for (i in 0 until UnhandledFrameCollector.MAX_ENTRIES) {
            collector.record("r", byteArrayOf(i.toByte()), 1000L + i)
        }

        // Recording an existing frame should still increment count
        collector.record("r", byteArrayOf(0x00), 9999L)
        val first = collector.getEntries()[0]
        assertEquals(2, first.count)
        assertEquals(9999L, first.lastSeenMs)
    }

    @Test
    fun `empty frame data is accepted`() {
        val collector = UnhandledFrameCollector()
        collector.record("empty", byteArrayOf(), 1000L)

        assertEquals(1, collector.count())
        assertEquals(0, collector.getEntries()[0].frameSize)
    }
}
