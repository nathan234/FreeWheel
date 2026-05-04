package org.freewheel.core.service

import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.protocol.hexToByteArray
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end pipeline tests for [WheelConnectionManager].
 *
 * Uses [FakeBleManager] with the real [DefaultWheelDecoderFactory] (not fakes)
 * to verify that real protocol packets flow through the full pipeline and
 * produce correct telemetry values.
 *
 * Packet data sourced from [KingsongDecoderComparisonTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerPipelineTest {

    private lateinit var fakeBle: FakeBleManager
    private val factory = DefaultWheelDecoderFactory()

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
    }

    private fun TestScope.createManager(): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(fakeBle, factory, backgroundScope, dispatcher)
    }

    // ==================== Real Kingsong Packets ====================
    // From KingsongDecoderComparisonTest — KS-S18 real data

    /** Model name frame (0xBB): "KS-S18-0205" */
    private val ksNamePacket = "aa554b532d5331382d30323035000000bb1484fd".hexToByteArray()

    /** Live data frame (0xA9): voltage=6505, speed=515, current=215, temp=13°C */
    private val ksLiveDataPacket = "aa556919030200009f36d700140500e0a9145a5a".hexToByteArray()

    /** Distance/fan/time frame (0xB9) */
    private val ksDistancePacket = "aa550000090017011502140100004006b9145a5a".hexToByteArray()

    /** CPU load frame (0xF5): cpuLoad=64 */
    private val ksCpuLoadPacket = "aa55000000000000000000000000400cf5145a5a".hexToByteArray()

    /** Output/speed-limit frame (0xF6): output=12, speedLimit=3205 */
    private val ksOutputPacket = "aa55850c010000000000000016000000f6145a5a".hexToByteArray()

    // ==================== Pipeline: Real packets → correct state ====================

    @Test
    fun `real Kingsong packets produce correct wheelState values`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed real packets in order
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)
        manager.onDataReceived(ksDistancePacket)
        manager.onDataReceived(ksCpuLoadPacket)
        manager.onDataReceived(ksOutputPacket)

        val tel = manager.telemetryState.value!!
        val id = manager.identityState.value

        // Voltage: 6505 (65.05V)
        assertEquals(6505, tel.voltage, "Voltage should be 6505 (65.05V)")

        // Model should be extracted from name packet
        assertEquals("KS-S18", id.model, "Model should be KS-S18")

        // Name should include version suffix
        assertTrue(id.name.startsWith("KS-S18"), "Name should start with KS-S18")

        // Wheel type should be preserved
        assertEquals(WheelType.KINGSONG, id.wheelType)
    }

    @Test
    fun `real Kingsong packets produce correct speed and temperature`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)

        val tel = manager.telemetryState.value!!

        // Speed from live data packet
        assertTrue(tel.speed > 0, "Speed should be positive after live data")

        // Temperature from live data packet
        assertTrue(tel.temperature > 0, "Temperature should be set from live data")
    }

    // ==================== connectionState transitions ====================

    @Test
    fun `connectionState transitions to Connected after isReady`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Before feeding data — not Connected
        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should not be Connected before decoder is ready"
        )

        // Feed name packet (sets model) + live data (sets voltage) → isReady() = true
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)

        // KingsongDecoder.isReady() = model.isNotEmpty() && hasReceivedVoltage
        val connState = manager.connectionState.value
        assertTrue(
            connState is ConnectionState.Connected,
            "Should transition to Connected after model + voltage received, got $connState"
        )
        assertEquals("AA:BB:CC:DD:EE:FF", (connState as ConnectionState.Connected).address)
    }

    @Test
    fun `connectionState not Connected until decoder has both model and voltage`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed only name packet — model set but no voltage yet
        manager.onDataReceived(ksNamePacket)

        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should not be Connected with model but no voltage"
        )

        // Now feed live data — voltage received → isReady
        manager.onDataReceived(ksLiveDataPacket)

        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should be Connected after voltage received"
        )
    }

    // ==================== Partial/incomplete data ====================

    @Test
    fun `partial data does not crash and state unchanged`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Set initial state with a valid packet
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)
        val telAfterValid = manager.telemetryState.value!!
        val idAfterValid = manager.identityState.value

        // Feed incomplete frame (too short for Kingsong 20-byte frame)
        manager.onDataReceived(byteArrayOf(0xAA.toByte(), 0x55))

        // State should be unchanged
        assertEquals(telAfterValid.voltage, manager.telemetryState.value!!.voltage)
        assertEquals(idAfterValid.model, manager.identityState.value.model)
    }

    @Test
    fun `empty data does not crash`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed empty array
        manager.onDataReceived(byteArrayOf())

        // Should still be null (no real data received)
        assertNull(manager.telemetryState.value, "Telemetry should be null before real data")
    }

    // ==================== consecutiveDecodeErrors ====================

    @Test
    fun `buffering and unhandled do not increment consecutiveDecodeErrors`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertEquals(0, manager.consecutiveDecodeErrors.value, "Should start at 0")

        // Garbage data → Kingsong returns Buffering (too short / bad header)
        manager.onDataReceived(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Buffering should NOT increment errors")

        // 20-byte frame with valid header but unknown frame type → Unhandled
        val unknownFrame = ByteArray(20).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[16] = 0x01 // unknown frame type
            it[17] = 0x14; it[18] = 0x5A; it[19] = 0x5A
        }
        manager.onDataReceived(unknownFrame)
        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Unhandled should NOT increment errors")
    }

    @Test
    fun `consecutiveDecodeErrors resets on valid decode`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // consecutiveDecodeErrors only increments on exceptions, which are hard to
        // trigger through real decoders. Verify the counter stays at 0 and resets properly.
        assertEquals(0, manager.consecutiveDecodeErrors.value)

        // Feed valid packet → counter stays at 0
        manager.onDataReceived(ksNamePacket)
        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should be 0 after valid decode")
    }

    @Test
    fun `consecutiveDecodeErrors resets on connect`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Verify counter starts at 0
        assertEquals(0, manager.consecutiveDecodeErrors.value)

        // Reconnect — counter should still be 0
        manager.disconnect()
        runCurrent()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should reset to 0 on new connect")
    }

    @Test
    fun `consecutiveDecodeErrors resets on disconnect`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Verify counter starts at 0
        assertEquals(0, manager.consecutiveDecodeErrors.value)

        manager.disconnect()
        runCurrent()

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should reset to 0 on disconnect")
    }

    // ==================== Granular sub-states ====================

    @Test
    fun `telemetryState updates on live data`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val initialTelemetry = manager.telemetryState.value

        // Feed live data
        manager.onDataReceived(ksLiveDataPacket)

        val newTelemetry = manager.telemetryState.value
        assertNotEquals(initialTelemetry, newTelemetry,
            "TelemetryState should update on live data")
        assertTrue(newTelemetry!!.voltage > 0, "Voltage should be set in telemetry")
    }

    @Test
    fun `identityState updates on name packet`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val initialIdentity = manager.identityState.value

        // Feed name packet
        manager.onDataReceived(ksNamePacket)

        val newIdentity = manager.identityState.value
        assertTrue(newIdentity.model.isNotEmpty(),
            "Model should be set in identity after name packet")
        assertEquals("KS-S18", newIdentity.model)
    }

    @Test
    fun `identityState wheelType set on decoder setup`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType,
            "Identity should have wheelType after setup")
    }

    @Test
    fun `telemetryState does not change on name-only packet`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val initialTelemetry = manager.telemetryState.value

        // Name packet changes identity, not telemetry
        manager.onDataReceived(ksNamePacket)

        assertEquals(initialTelemetry, manager.telemetryState.value,
            "TelemetryState should NOT change on name-only packet")
    }

    // ==================== Multi-packet integration ====================

    @Test
    fun `full packet sequence produces complete state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed all 5 packets
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)
        manager.onDataReceived(ksDistancePacket)
        manager.onDataReceived(ksCpuLoadPacket)
        manager.onDataReceived(ksOutputPacket)

        val tel = manager.telemetryState.value!!
        val id = manager.identityState.value

        // After full sequence, should have: model, voltage, speed, battery, distance
        assertTrue(id.model.isNotEmpty(), "Model should be set")
        assertTrue(tel.voltage > 0, "Voltage should be set")
        assertTrue(tel.batteryLevel > 0, "Battery level should be computed")
        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should be Connected after full sequence"
        )
    }

    @Test
    fun `recovery from garbage to valid data`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed garbage — no crash, Buffering/Unhandled don't increment errors
        repeat(3) {
            manager.onDataReceived(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
        }
        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Buffering should not increment errors")

        // Feed valid data — state updates normally
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Errors should remain 0 after valid data")
        assertTrue(manager.telemetryState.value!!.voltage > 0,
            "State should update after recovery")
        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should reach Connected after recovery"
        )
    }

    // ==================== Decode exception safety ====================

    @Test
    fun `decode exception does not crash and increments errors`() = runTest(timeout = 0.1.seconds) {
        // Create a factory that returns a decoder which throws on decode()
        val throwingFactory = object : WheelDecoderFactory {
            override fun createDecoder(wheelType: WheelType): WheelDecoder? {
                return object : WheelDecoder {
                    override val wheelType = WheelType.KINGSONG
                    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
                        throw RuntimeException("Simulated decoder crash")
                    }
                    override fun isReady(): Boolean = false
                    override fun reset() {}
                }
            }
            override fun supportedTypes() = listOf(WheelType.KINGSONG)
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = WheelConnectionManager(fakeBle, throwingFactory, backgroundScope, dispatcher)
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val telemetryBefore = manager.telemetryState.value

        // Feed data — decoder throws, but manager catches it
        manager.onDataReceived(ksLiveDataPacket)

        // Should not crash, errors should increment
        assertTrue(
            manager.consecutiveDecodeErrors.value > 0,
            "Should increment decode errors on exception"
        )

        // State should be unchanged (still null — no successful decode)
        assertEquals(telemetryBefore, manager.telemetryState.value,
            "State should not change when decoder throws")
    }
}
