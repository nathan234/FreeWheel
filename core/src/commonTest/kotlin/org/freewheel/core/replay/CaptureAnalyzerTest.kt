package org.freewheel.core.replay

import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecodedData
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.UnhandledReason
import org.freewheel.core.protocol.UnpackerStats
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureAnalyzerTest {

    // Decoder that reads speed from first 2 bytes (big-endian), voltage from bytes 2-3.
    // Byte 0 determines frame type: 0x01=TELEMETRY, 0x02=SETTINGS, others=TELEMETRY.
    // 0xFF 0xFF triggers Unhandled with detail "0xFF".
    private class TestDecoder(
        private val unpackerStatsToReport: UnpackerStats? = null
    ) : WheelDecoder {
        override val wheelType = WheelType.KINGSONG
        private var ready = false

        override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
            if (data.size < 2) return DecodeResult.Buffering
            if (data[0] == 0xFF.toByte() && data[1] == 0xFF.toByte()) {
                return DecodeResult.Unhandled(
                    UnhandledReason.UnknownCommand(frameHex = "0xFF"),
                    data
                )
            }
            val frameType = if (data[0].toInt() and 0xFF == 0x02) "SETTINGS" else "TELEMETRY"
            val speed = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val voltage = if (data.size >= 4) {
                ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            } else {
                currentState.telemetry.voltage
            }
            ready = true
            return DecodeResult.Success(DecodedData(
                telemetry = currentState.telemetry.copy(speed = speed, voltage = voltage),
                identity = currentState.identity.copy(wheelType = WheelType.KINGSONG),
                commands = if (speed > 1000) listOf(WheelCommand.Beep) else emptyList(),
                frameTypes = listOf(frameType)
            ))
        }

        override fun isReady(): Boolean = ready
        override fun reset() { ready = false }
        override fun getUnpackerStats(): UnpackerStats? = unpackerStatsToReport
    }

    // Decoder that always throws
    private class CrashingDecoder : WheelDecoder {
        override val wheelType = WheelType.GOTWAY
        override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
            throw RuntimeException("decode exploded")
        }
        override fun isReady(): Boolean = false
        override fun reset() {}
    }

    private class TestDecoderFactory : WheelDecoderFactory {
        override fun createDecoder(wheelType: WheelType): WheelDecoder? = when (wheelType) {
            WheelType.KINGSONG -> TestDecoder()
            WheelType.GOTWAY -> CrashingDecoder()
            WheelType.Unknown -> null
            else -> TestDecoder()
        }
        override fun supportedTypes() = listOf(WheelType.KINGSONG, WheelType.GOTWAY)
    }

    private val analyzer = CaptureAnalyzer(TestDecoderFactory())

    private fun makeCapture(
        wheelType: WheelType = WheelType.KINGSONG,
        packets: List<CapturedPacket> = emptyList(),
        markers: List<CapturedMarker> = emptyList()
    ): CaptureFile {
        val entries = mutableListOf<CaptureEntry>()
        packets.forEach { entries.add(CaptureEntry.Packet(it)) }
        markers.forEach { entries.add(CaptureEntry.Marker(it)) }
        entries.sortBy { it.timestampMs }

        val duration = if (entries.size >= 2) {
            entries.last().timestampMs - entries.first().timestampMs
        } else 0L

        return CaptureFile(
            header = CaptureHeader(wheelType, wheelType.name, "TestWheel", "1.0", "1.0"),
            entries = entries,
            durationMs = duration
        )
    }

    private fun rxPacket(timestampMs: Long, vararg bytes: Int): CapturedPacket {
        return CapturedPacket(
            timestampMs = timestampMs,
            direction = BlePacketDirection.RX,
            data = ByteArray(bytes.size) { bytes[it].toByte() }
        )
    }

    private fun txPacket(timestampMs: Long): CapturedPacket {
        return CapturedPacket(
            timestampMs = timestampMs,
            direction = BlePacketDirection.TX,
            data = byteArrayOf(0x01, 0x02)
        )
    }

    // --- Basic analysis ---

    @Test
    fun analyzeSuccessfulPackets() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64, 0x20, 0x00), // speed=100, voltage=8192
            rxPacket(1100, 0x01, 0xF4, 0x20, 0x00)  // speed=500, voltage=8192
        ))

        val result = analyzer.analyze(capture)
        assertNotNull(result)

        assertEquals(2, result.summary.rxPackets)
        assertEquals(0, result.summary.txPackets)
        assertEquals(2, result.summary.successCount)
        assertEquals(0, result.summary.bufferingCount)
        assertEquals(0, result.summary.unhandledCount)
        assertEquals(0, result.summary.errorCount)
        assertEquals(500, result.finalState.telemetry.speed)
        assertEquals(8192, result.finalState.telemetry.voltage)
    }

    @Test
    fun analyzeTracksStateChanges() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64, 0x20, 0x00), // speed=100, voltage=8192
            rxPacket(1100, 0x01, 0xF4, 0x20, 0x00)  // speed=500, voltage=8192 (unchanged)
        ))

        val result = analyzer.analyze(capture)!!

        // First packet: speed and voltage change from defaults (0)
        val first = result.packets[0]
        val speedChange = first.stateChanges.find { it.field == "speed" }
        assertNotNull(speedChange)
        assertEquals("0", speedChange.oldValue)
        assertEquals("100", speedChange.newValue)

        val voltageChange = first.stateChanges.find { it.field == "voltage" }
        assertNotNull(voltageChange)
        assertEquals("0", voltageChange.oldValue)
        assertEquals("8192", voltageChange.newValue)

        // Second packet: only speed changes
        val second = result.packets[1]
        val speedChange2 = second.stateChanges.find { it.field == "speed" }
        assertNotNull(speedChange2)
        assertEquals("100", speedChange2.oldValue)
        assertEquals("500", speedChange2.newValue)

        // Voltage didn't change
        val voltageChange2 = second.stateChanges.find { it.field == "voltage" }
        assertNull(voltageChange2)
    }

    @Test
    fun analyzeTxPacketsSkipped() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64),
            txPacket(1050),
            rxPacket(1100, 0x01, 0xF4)
        ))

        val result = analyzer.analyze(capture)!!

        assertEquals(2, result.summary.rxPackets)
        assertEquals(1, result.summary.txPackets)
        assertEquals(3, result.summary.totalPackets)

        // TX packet is annotated as Skipped
        val txAnnotated = result.packets[1]
        assertEquals(BlePacketDirection.TX, txAnnotated.direction)
        assertTrue(txAnnotated.result is PacketResult.Skipped)
    }

    @Test
    fun analyzeBufferingPackets() {
        // Single-byte packet triggers Buffering in TestDecoder
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x42)
        ))

        val result = analyzer.analyze(capture)!!

        assertEquals(1, result.summary.bufferingCount)
        assertEquals(0, result.summary.successCount)
        assertTrue(result.packets[0].result is PacketResult.Buffering)
    }

    @Test
    fun analyzeUnhandledPackets() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0xFF, 0xFF), // triggers Unhandled in TestDecoder
            rxPacket(1100, 0xFF, 0xFF)
        ))

        val result = analyzer.analyze(capture)!!

        assertEquals(2, result.summary.unhandledCount)
        assertEquals(0, result.summary.successCount)
        assertEquals(mapOf("unknown_command:0xFF" to 2), result.summary.unhandledReasons)

        val packet = result.packets[0]
        assertTrue(packet.result is PacketResult.Unhandled)
        assertEquals("unknown_command:0xFF", (packet.result as PacketResult.Unhandled).reason)
    }

    @Test
    fun analyzeDecoderException() {
        val capture = makeCapture(
            wheelType = WheelType.GOTWAY,
            packets = listOf(rxPacket(1000, 0x01, 0x02))
        )

        val result = analyzer.analyze(capture)!!

        assertEquals(1, result.summary.errorCount)
        assertEquals(0, result.summary.successCount)

        val packet = result.packets[0]
        assertTrue(packet.result is PacketResult.Error)
        assertEquals("decode exploded", (packet.result as PacketResult.Error).message)
    }

    @Test
    fun analyzeUnsupportedWheelTypeReturnsNull() {
        val capture = makeCapture(wheelType = WheelType.Unknown)
        assertNull(analyzer.analyze(capture))
    }

    @Test
    fun analyzeMarkersIgnored() {
        val capture = makeCapture(
            packets = listOf(rxPacket(1000, 0x00, 0x64)),
            markers = listOf(CapturedMarker(1050, "test marker"))
        )

        val result = analyzer.analyze(capture)!!

        // Only the RX packet appears in annotated packets (markers are skipped)
        assertEquals(1, result.packets.size)
        assertEquals(1, result.summary.rxPackets)
    }

    @Test
    fun analyzeCommandsTracked() {
        // speed > 1000 triggers a Beep command in TestDecoder
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x04, 0x00) // speed = 1024
        ))

        val result = analyzer.analyze(capture)!!
        assertEquals(listOf("Beep"), result.packets[0].commands)
    }

    @Test
    fun analyzeNoCommandsWhenBelowThreshold() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64) // speed = 100
        ))

        val result = analyzer.analyze(capture)!!
        assertTrue(result.packets[0].commands.isEmpty())
    }

    @Test
    fun analyzeMixedResults() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x42),       // Buffering (1 byte)
            rxPacket(1100, 0x00, 0x64), // Success
            txPacket(1150),             // Skipped
            rxPacket(1200, 0xFF, 0xFF), // Unhandled
            rxPacket(1300, 0x01, 0xF4)  // Success
        ))

        val result = analyzer.analyze(capture)!!

        assertEquals(4, result.summary.rxPackets)
        assertEquals(1, result.summary.txPackets)
        assertEquals(2, result.summary.successCount)
        assertEquals(1, result.summary.bufferingCount)
        assertEquals(1, result.summary.unhandledCount)
        assertEquals(0, result.summary.errorCount)
        assertEquals(500, result.finalState.telemetry.speed)
    }

    @Test
    fun analyzeEmptyCapture() {
        val capture = makeCapture(packets = emptyList())

        val result = analyzer.analyze(capture)!!

        assertEquals(0, result.summary.totalPackets)
        assertEquals(0, result.summary.successCount)
        assertEquals(DecoderState(), result.finalState)
    }

    // --- Report formatting ---

    @Test
    fun formatReportContainsHeaderAndSummary() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64),
            rxPacket(1100, 0xFF, 0xFF)
        ))

        val report = analyzer.analyze(capture)!!.formatReport()

        assertTrue(report.contains("KINGSONG"))
        assertTrue(report.contains("TestWheel"))
        assertTrue(report.contains("Success: 1"))
        assertTrue(report.contains("Unhandled: 1"))
        assertTrue(report.contains("unknown_command:0xFF"))
    }

    @Test
    fun formatReportHidesBufferingByDefault() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x42),       // Buffering
            rxPacket(1100, 0x00, 0x64)  // Success
        ))

        val report = analyzer.analyze(capture)!!.formatReport()
        // Buffering packets omitted by default
        assertTrue(report.contains("success"))
        kotlin.test.assertFalse(
            report.lines().any { it.contains("| buffering |") },
            "buffering line should be omitted"
        )
    }

    @Test
    fun formatReportIncludesBufferingWhenRequested() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x42)
        ))

        val report = analyzer.analyze(capture)!!.formatReport(includeBuffering = true)
        assertTrue(report.contains("buffering"))
    }

    @Test
    fun formatReportTruncatesLongHex() {
        val longData = ByteArray(30) { it.toByte() }
        val packet = CapturedPacket(1000, BlePacketDirection.RX, longData)
        val capture = makeCapture(packets = listOf(packet))

        // Use a shorter maxHexLength to test truncation
        val report = analyzer.analyze(capture)!!.formatReport(maxHexLength = 20)
        assertTrue(report.contains("..."))
    }

    // --- diffStates ---

    @Test
    fun diffStatesDetectsSpeedChange() {
        val old = DecoderState(telemetry = org.freewheel.core.domain.TelemetryState(speed = 100))
        val new = DecoderState(telemetry = org.freewheel.core.domain.TelemetryState(speed = 200))

        val changes = diffStates(old, new)

        assertEquals(1, changes.size)
        assertEquals("speed", changes[0].field)
        assertEquals("100", changes[0].oldValue)
        assertEquals("200", changes[0].newValue)
    }

    @Test
    fun diffStatesDetectsMultipleChanges() {
        val old = DecoderState(telemetry = org.freewheel.core.domain.TelemetryState(speed = 100, voltage = 8400, temperature = 3500))
        val new = DecoderState(telemetry = org.freewheel.core.domain.TelemetryState(speed = 200, voltage = 8300, temperature = 3500))

        val changes = diffStates(old, new)

        assertEquals(2, changes.size)
        val fields = changes.map { it.field }.toSet()
        assertTrue("speed" in fields)
        assertTrue("voltage" in fields)
    }

    @Test
    fun diffStatesNoChanges() {
        val state = DecoderState(telemetry = org.freewheel.core.domain.TelemetryState(speed = 100, voltage = 8400))
        val changes = diffStates(state, state)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun diffStatesDetectsIdentityChanges() {
        val old = DecoderState()
        val new = DecoderState(identity = org.freewheel.core.domain.WheelIdentity(model = "V14", version = "2.3.7", wheelType = WheelType.INMOTION_V2))

        val changes = diffStates(old, new)
        val fields = changes.map { it.field }.toSet()
        assertTrue("model" in fields)
        assertTrue("version" in fields)
        assertTrue("wheelType" in fields)
    }

    @Test
    fun diffStatesDetectsSettingsChanges() {
        val old = DecoderState()
        val new = DecoderState(settings = org.freewheel.core.domain.WheelSettings.Kingsong(pedalsMode = 1, lightMode = 2))

        val changes = diffStates(old, new)
        val fields = changes.map { it.field }.toSet()
        assertTrue("settings" in fields)
    }

    // --- Frame type distribution ---

    @Test
    fun analyzeTracksFrameTypeDistribution() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64, 0x20, 0x00), // TELEMETRY (byte 0 != 0x02)
            rxPacket(1100, 0x00, 0x65, 0x20, 0x00), // TELEMETRY
            rxPacket(1200, 0x02, 0x01, 0x20, 0x00)  // SETTINGS (byte 0 == 0x02)
        ))

        val result = analyzer.analyze(capture)!!

        val dist = result.summary.frameTypeDistribution
        assertEquals(2, dist.size)

        val telemetry = dist.find { it.frameType == "TELEMETRY" }
        assertNotNull(telemetry)
        assertEquals(2, telemetry.count)
        kotlin.test.assertFalse(telemetry.isUnhandled)

        val settings = dist.find { it.frameType == "SETTINGS" }
        assertNotNull(settings)
        assertEquals(1, settings.count)
        kotlin.test.assertFalse(settings.isUnhandled)
    }

    @Test
    fun analyzeTracksUnhandledFrameTypes() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64), // TELEMETRY
            rxPacket(1100, 0xFF, 0xFF), // Unhandled: 0xFF
            rxPacket(1200, 0xFF, 0xFF)  // Unhandled: 0xFF
        ))

        val result = analyzer.analyze(capture)!!

        val dist = result.summary.frameTypeDistribution
        val telemetry = dist.find { it.frameType == "TELEMETRY" && !it.isUnhandled }
        assertNotNull(telemetry)
        assertEquals(1, telemetry.count)

        val unhandled = dist.find { it.isUnhandled }
        assertNotNull(unhandled)
        assertEquals(2, unhandled.count)
        assertTrue(unhandled.isUnhandled)
    }

    @Test
    fun analyzeDistributionSortedByCountDescending() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x02, 0x01, 0x20, 0x00), // SETTINGS
            rxPacket(1100, 0x00, 0x64),              // TELEMETRY
            rxPacket(1200, 0x00, 0x65),              // TELEMETRY
            rxPacket(1300, 0x00, 0x66)               // TELEMETRY
        ))

        val result = analyzer.analyze(capture)!!

        val successDist = result.summary.frameTypeDistribution.filter { !it.isUnhandled }
        assertEquals(2, successDist.size)
        // TELEMETRY (3) should come before SETTINGS (1)
        assertEquals("TELEMETRY", successDist[0].frameType)
        assertEquals(3, successDist[0].count)
        assertEquals("SETTINGS", successDist[1].frameType)
        assertEquals(1, successDist[1].count)
    }

    @Test
    fun analyzeIncludesUnpackerStats() {
        val statsDecoder = TestDecoder(unpackerStatsToReport = UnpackerStats(errorResets = 5, bytesDiscarded = 120))
        val factory = object : WheelDecoderFactory {
            override fun createDecoder(wheelType: WheelType): WheelDecoder = statsDecoder
            override fun supportedTypes() = listOf(WheelType.KINGSONG)
        }
        val analyzerWithStats = CaptureAnalyzer(factory)

        val capture = makeCapture(packets = listOf(rxPacket(1000, 0x00, 0x64)))
        val result = analyzerWithStats.analyze(capture)!!

        assertEquals(5, result.summary.unpackerStats.errorResets)
        assertEquals(120, result.summary.unpackerStats.bytesDiscarded)
    }

    @Test
    fun formatReportIncludesDistributionHistogram() {
        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 0x00, 0x64),              // TELEMETRY
            rxPacket(1100, 0x00, 0x65),              // TELEMETRY
            rxPacket(1200, 0x02, 0x01, 0x20, 0x00),  // SETTINGS
            rxPacket(1300, 0xFF, 0xFF)                // Unhandled
        ))

        val report = analyzer.analyze(capture)!!.formatReport()

        assertTrue(report.contains("Frame Type Distribution"))
        assertTrue(report.contains("TELEMETRY"))
        assertTrue(report.contains("SETTINGS"))
        assertTrue(report.contains("ok"))
        assertTrue(report.contains("unhandled"))
    }

    @Test
    fun formatReportIncludesUnpackerStats() {
        val statsDecoder = TestDecoder(unpackerStatsToReport = UnpackerStats(errorResets = 3, bytesDiscarded = 45))
        val factory = object : WheelDecoderFactory {
            override fun createDecoder(wheelType: WheelType): WheelDecoder = statsDecoder
            override fun supportedTypes() = listOf(WheelType.KINGSONG)
        }
        val analyzerWithStats = CaptureAnalyzer(factory)

        val capture = makeCapture(packets = listOf(rxPacket(1000, 0x00, 0x64)))
        val report = analyzerWithStats.analyze(capture)!!.formatReport()

        assertTrue(report.contains("Unpacker resets: 3"))
        assertTrue(report.contains("45 bytes discarded"))
    }

    @Test
    fun analyzeEmptyCaptureHasEmptyDistribution() {
        val capture = makeCapture(packets = emptyList())
        val result = analyzer.analyze(capture)!!

        assertTrue(result.summary.frameTypeDistribution.isEmpty())
        assertEquals(0, result.summary.unpackerStats.errorResets)
        assertEquals(0, result.summary.unpackerStats.bytesDiscarded)
    }

}
