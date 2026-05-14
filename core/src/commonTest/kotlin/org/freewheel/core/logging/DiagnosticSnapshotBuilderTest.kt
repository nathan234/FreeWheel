package org.freewheel.core.logging

import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.identity.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.protocol.DecoderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticSnapshotBuilderTest {

    // ==================== BT Name Sanitization ====================

    @Test
    fun `sanitize strips serial suffix with 4+ digits`() {
        assertEquals("P6", DiagnosticSnapshotBuilder.sanitizeBtName("P6-50002437"))
    }

    @Test
    fun `sanitize keeps name when suffix has fewer than 4 digits`() {
        assertEquals("KS-14D", DiagnosticSnapshotBuilder.sanitizeBtName("KS-14D"))
    }

    @Test
    fun `sanitize strips underscore serial suffix`() {
        assertEquals("VT", DiagnosticSnapshotBuilder.sanitizeBtName("VT_12345678"))
    }

    @Test
    fun `sanitize keeps name with no separator`() {
        assertEquals("LPKIM1234567", DiagnosticSnapshotBuilder.sanitizeBtName("LPKIM1234567"))
    }

    @Test
    fun `sanitize returns empty for empty input`() {
        assertEquals("", DiagnosticSnapshotBuilder.sanitizeBtName(""))
    }

    @Test
    fun `sanitize keeps name when suffix has exactly 3 digits`() {
        assertEquals("GW-B123", DiagnosticSnapshotBuilder.sanitizeBtName("GW-B123"))
    }

    @Test
    fun `sanitize strips when suffix is all digits 4+`() {
        assertEquals("NB", DiagnosticSnapshotBuilder.sanitizeBtName("NB-1234"))
    }

    // ==================== Snapshot Building ====================

    @Test
    fun `builds snapshot with correct identity fields`() {
        val identity = WheelIdentity(
            wheelType = WheelType.INMOTION_V2,
            model = "P6",
            btName = "P6-50002437",
            version = "2.0.1.4"
        )
        val caps = CapabilitySet(
            detectedModel = "V14",
            firmwareVersion = "2.0.1.4",
            firmwareLevel = 20014,
            isResolved = true,
            supportedCommands = setOf(SettingsCommandId.MAX_SPEED, SettingsCommandId.PEDAL_TILT)
        )
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "android", "1.2.3"
        )

        assertEquals(WheelType.INMOTION_V2, snapshot.wheelType)
        assertEquals("V14", snapshot.detectedModel)
        assertEquals("P6", snapshot.btNamePrefix)
        assertEquals("2.0.1.4", snapshot.firmwareVersion)
        assertEquals(20014, snapshot.firmwareLevel)
        assertTrue(snapshot.capabilitiesResolved)
        assertEquals(listOf("MAX_SPEED", "PEDAL_TILT"), snapshot.supportedCommands)
        assertEquals("android", snapshot.platform)
        assertEquals("1.2.3", snapshot.appVersion)
    }

    @Test
    fun `falls back to identity model when capabilities detectedModel is empty`() {
        val identity = WheelIdentity(wheelType = WheelType.GOTWAY, model = "T4")
        val caps = CapabilitySet()
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "ios", "1.0.0"
        )

        assertEquals("T4", snapshot.detectedModel)
    }

    @Test
    fun `falls back to identity version when capabilities firmwareVersion is empty`() {
        val identity = WheelIdentity(wheelType = WheelType.KINGSONG, version = "2.07")
        val caps = CapabilitySet()
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "android", "1.0.0"
        )

        assertEquals("2.07", snapshot.firmwareVersion)
    }

    @Test
    fun `includes BLE UUIDs from connectionInfo`() {
        val identity = WheelIdentity(wheelType = WheelType.KINGSONG)
        val connInfo = WheelConnectionInfo.forKingsong()
        val caps = CapabilitySet()
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, connInfo, config, "android", "1.0.0"
        )

        assertTrue(snapshot.readServiceUuid.isNotEmpty())
        assertTrue(snapshot.writeServiceUuid.isNotEmpty())
    }

    @Test
    fun `null connectionInfo produces empty UUIDs`() {
        val identity = WheelIdentity(wheelType = WheelType.GOTWAY)
        val caps = CapabilitySet()
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "android", "1.0.0"
        )

        assertEquals("", snapshot.readServiceUuid)
        assertEquals("", snapshot.writeServiceUuid)
    }

    @Test
    fun `includes decoder config fields`() {
        val identity = WheelIdentity(wheelType = WheelType.GOTWAY)
        val caps = CapabilitySet()
        val config = DecoderConfig(
            gotwayNegative = 1,
            useRatio = true,
            gotwayVoltage = 2,
            hwPwmEnabled = false,
            autoVoltage = false
        )

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "android", "1.0.0"
        )

        assertEquals(1, snapshot.gotwayNegative)
        assertTrue(snapshot.useRatio)
        assertEquals(2, snapshot.gotwayVoltage)
        assertFalse(snapshot.hwPwmEnabled)
        assertFalse(snapshot.autoVoltage)
    }

    @Test
    fun `unresolved capabilities produces capabilitiesResolved false`() {
        val identity = WheelIdentity(wheelType = WheelType.VETERAN)
        val caps = CapabilitySet(isResolved = false)
        val config = DecoderConfig()

        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, config, "ios", "1.0.0"
        )

        assertFalse(snapshot.capabilitiesResolved)
    }

    // ==================== Comment Block Format ====================

    @Test
    fun `comment block lines all start with hash`() {
        val snapshot = buildTestSnapshot()
        val block = DiagnosticSnapshotBuilder.formatAsCommentBlock(snapshot)

        for (line in block.lines()) {
            assertTrue(line.startsWith("#"), "Line does not start with #: $line")
        }
    }

    @Test
    fun `comment block contains key fields`() {
        val snapshot = buildTestSnapshot()
        val block = DiagnosticSnapshotBuilder.formatAsCommentBlock(snapshot)

        assertTrue(block.contains("wheel_type: INMOTION_V2"))
        assertTrue(block.contains("detected_model: P6"))
        assertTrue(block.contains("bt_name_prefix: P6"))
        assertTrue(block.contains("capabilities_resolved: true"))
        assertTrue(block.contains("platform: android"))
    }

    // ==================== Text Format ====================

    @Test
    fun `text format contains key fields`() {
        val snapshot = buildTestSnapshot()
        val text = DiagnosticSnapshotBuilder.formatAsText(snapshot)

        assertTrue(text.contains("Wheel Type: INMOTION_V2"))
        assertTrue(text.contains("Detected Model: P6"))
        assertTrue(text.contains("BT Name: P6"))
        assertTrue(text.contains("Capabilities Resolved: true"))
        assertTrue(text.contains("Platform: android"))
    }

    @Test
    fun `text format does not contain serial number`() {
        val snapshot = buildTestSnapshot()
        val text = DiagnosticSnapshotBuilder.formatAsText(snapshot)

        assertFalse(text.contains("50002437"))
        assertFalse(text.contains("serial", ignoreCase = true))
    }

    @Test
    fun `comment block does not contain serial number`() {
        val snapshot = buildTestSnapshot()
        val block = DiagnosticSnapshotBuilder.formatAsCommentBlock(snapshot)

        assertFalse(block.contains("50002437"))
        assertFalse(block.contains("serial", ignoreCase = true))
    }

    // ==================== BleCaptureLogger Footer Integration ====================

    @Test
    fun `stop with footer writes footer lines before close`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "INMOTION_V2", "P6", "2.0.1.4", "1.0.0", 1000)
        logger.logPacket(byteArrayOf(0x01, 0x02), BlePacketDirection.RX, 1100)

        val footer = "# --- Diagnostic Info ---\n# wheel_type: INMOTION_V2\n# detected_model: P6"
        val metadata = logger.stop(2000, footer)

        // Metadata should still be returned correctly
        assertEquals(1, metadata?.packetCount)
        assertEquals("INMOTION_V2", metadata?.wheelTypeName)
    }

    @Test
    fun `stop with null footer works as before`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 1000)
        val metadata = logger.stop(2000, null)

        assertEquals("KINGSONG", metadata?.wheelTypeName)
    }

    @Test
    fun `stop without footer parameter works as before`() {
        val logger = BleCaptureLogger()
        logger.start(createTempPath(), "KINGSONG", "S22", "2.07", "1.0.0", 1000)
        val metadata = logger.stop(2000)

        assertEquals("KINGSONG", metadata?.wheelTypeName)
    }

    // ==================== Helpers ====================

    private fun buildTestSnapshot(): DiagnosticSnapshot {
        val identity = WheelIdentity(
            wheelType = WheelType.INMOTION_V2,
            model = "P6",
            btName = "P6-50002437",
            version = "2.0.1.4",
            serialNumber = "SN50002437"
        )
        val caps = CapabilitySet(
            detectedModel = "P6",
            firmwareVersion = "2.0.1.4",
            firmwareLevel = 20014,
            isResolved = true,
            supportedCommands = setOf(SettingsCommandId.MAX_SPEED)
        )
        return DiagnosticSnapshotBuilder.buildSnapshot(
            identity, caps, null, DecoderConfig(), "android", "1.2.3"
        )
    }

    private var tempCounter = 0

    private fun createTempPath(name: String? = null): String {
        val fileName = name ?: "diag_test_${tempCounter++}.csv"
        return "/tmp/freewheel_test/$fileName"
    }
}
