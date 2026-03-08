package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BleCaptureLogger].
 *
 * Uses real FileWriter with temp paths (same pattern as RideLoggerTest).
 */
class BleCaptureLoggerTest {

    // ==================== Start/Stop Lifecycle ====================

    @Test
    fun `isCapturing is false initially`() {
        val logger = BleCaptureLogger()
        assertFalse(logger.isCapturing)
    }

    @Test
    fun `start sets isCapturing to true`() {
        val logger = BleCaptureLogger()
        val result = logger.start(
            createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 1000
        )
        assertTrue(result)
        assertTrue(logger.isCapturing)
        logger.stop(2000)
    }

    @Test
    fun `start while already capturing returns false`() {
        val logger = BleCaptureLogger()
        assertTrue(logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 1000))
        assertFalse(logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 2000))
        logger.stop(3000)
    }

    @Test
    fun `stop sets isCapturing to false`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 1000)
        logger.stop(2000)
        assertFalse(logger.isCapturing)
    }

    @Test
    fun `stop while not capturing returns null`() {
        val logger = BleCaptureLogger()
        assertNull(logger.stop(1000))
    }

    // ==================== Metadata ====================

    @Test
    fun `stop returns metadata with correct duration`() {
        val logger = BleCaptureLogger()
        val startMs = 10_000L
        val endMs = 70_000L // 60 seconds

        logger.start(createTempPath(), "INMOTION_V2", "P6", "2.0.1.4", "1.0.0", startMs)
        val metadata = logger.stop(endMs)

        assertNotNull(metadata)
        assertEquals(60, metadata.durationSeconds)
        assertEquals(startMs, metadata.startTimeMillis)
        assertEquals(endMs, metadata.endTimeMillis)
    }

    @Test
    fun `stop returns correct wheel info`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "GOTWAY", "Begode T4", "1.3.5", "1.0.0", 1000)
        val metadata = logger.stop(2000)

        assertNotNull(metadata)
        assertEquals("GOTWAY", metadata.wheelTypeName)
        assertEquals("Begode T4", metadata.wheelName)
    }

    @Test
    fun `fileName is extracted from path`() {
        val logger = BleCaptureLogger()
        val path = createTempPath("capture_test.csv")
        logger.start(path, "KINGSONG", "S22", "2.07", "1.0.0", 0)
        val metadata = logger.stop(1000)

        assertNotNull(metadata)
        assertEquals("capture_test.csv", metadata.fileName)
    }

    // ==================== Packet Counting ====================

    @Test
    fun `logPacket increments packet counts`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 0)

        logger.logPacket(byteArrayOf(0xAA.toByte(), 0x55), BlePacketDirection.RX, 100)
        logger.logPacket(byteArrayOf(0xAA.toByte(), 0x55), BlePacketDirection.RX, 200)
        logger.logPacket(byteArrayOf(0x01, 0x02), BlePacketDirection.TX, 300)

        val metadata = logger.stop(1000)
        assertNotNull(metadata)
        assertEquals(3, metadata.packetCount)
        assertEquals(2, metadata.rxPacketCount)
        assertEquals(1, metadata.txPacketCount)
    }

    @Test
    fun `logPacket is no-op when not capturing`() {
        val logger = BleCaptureLogger()
        // Don't call start
        logger.logPacket(byteArrayOf(0x01), BlePacketDirection.RX, 100)
        // No crash, no state change
        assertNull(logger.stop(200))
    }

    // ==================== Markers ====================

    @Test
    fun `insertMarker increments marker count`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 0)

        logger.insertMarker("toggled light", 100)
        logger.insertMarker("changed pedals mode", 200)

        val metadata = logger.stop(1000)
        assertNotNull(metadata)
        assertEquals(2, metadata.markerCount)
        assertEquals(0, metadata.packetCount)
    }

    @Test
    fun `insertMarker is no-op when not capturing`() {
        val logger = BleCaptureLogger()
        logger.insertMarker("test", 100)
        assertNull(logger.stop(200))
    }

    // ==================== Mixed Packets and Markers ====================

    @Test
    fun `mixed packets and markers counted correctly`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "INMOTION_V2", "P6", "2.0.1.4", "1.0.0", 0)

        logger.logPacket(byteArrayOf(0xAA.toByte(), 0x55), BlePacketDirection.RX, 100)
        logger.insertMarker("toggled light", 150)
        logger.logPacket(byteArrayOf(0x01, 0x02), BlePacketDirection.TX, 200)
        logger.logPacket(byteArrayOf(0xAA.toByte(), 0x55), BlePacketDirection.RX, 250)

        val metadata = logger.stop(1000)
        assertNotNull(metadata)
        assertEquals(3, metadata.packetCount)
        assertEquals(2, metadata.rxPacketCount)
        assertEquals(1, metadata.txPacketCount)
        assertEquals(1, metadata.markerCount)
    }

    // ==================== Double-Stop Idempotency ====================

    @Test
    fun `double stop returns null the second time`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 0)
        logger.logPacket(byteArrayOf(0x01), BlePacketDirection.RX, 100)

        val first = logger.stop(5000)
        assertNotNull(first)
        assertFalse(logger.isCapturing)

        val second = logger.stop(6000)
        assertNull(second)
        assertFalse(logger.isCapturing)
    }

    @Test
    fun `can start new session after stop`() {
        val logger = BleCaptureLogger()

        // First session
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 0)
        logger.logPacket(byteArrayOf(0x01), BlePacketDirection.RX, 100)
        val meta1 = logger.stop(2000)
        assertNotNull(meta1)
        assertEquals(1, meta1.packetCount)

        // Second session — counts reset
        assertTrue(logger.start(createTempPath(), "GOTWAY", "T4", "1.3", "1.0.0", 10000))
        logger.logPacket(byteArrayOf(0x02), BlePacketDirection.TX, 10100)
        logger.logPacket(byteArrayOf(0x03), BlePacketDirection.TX, 10200)
        val meta2 = logger.stop(12000)
        assertNotNull(meta2)
        assertEquals(2, meta2.packetCount)
        assertEquals(0, meta2.rxPacketCount)
        assertEquals(2, meta2.txPacketCount)
        assertEquals("GOTWAY", meta2.wheelTypeName)
    }

    // ==================== Zero-Duration Capture ====================

    @Test
    fun `zero duration capture returns zero seconds`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 5000)
        val metadata = logger.stop(5000)
        assertNotNull(metadata)
        assertEquals(0, metadata.durationSeconds)
    }

    // ==================== Helpers ====================

    private var tempCounter = 0

    private fun createTempPath(name: String? = null): String {
        val fileName = name ?: "capture_test_${tempCounter++}.csv"
        return "/tmp/freewheel_test/$fileName"
    }
}
