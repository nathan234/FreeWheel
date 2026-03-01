package com.cooper.wheellog.core.service

import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.protocol.DefaultWheelDecoderFactory
import com.cooper.wheellog.core.protocol.DecoderConfig
import com.cooper.wheellog.core.protocol.hexToByteArray
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
import kotlin.test.assertTrue

/**
 * End-to-end pipeline tests for [WheelConnectionManager].
 *
 * Uses [FakeBleManager] with the real [DefaultWheelDecoderFactory] (not fakes)
 * to verify that real protocol packets flow through the full pipeline and
 * produce correct [WheelState] values.
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
        return WheelConnectionManager(fakeBle, factory, this, dispatcher)
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
    fun `real Kingsong packets produce correct wheelState values`() = runTest {
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

        val state = manager.wheelState.value

        // Voltage: 6505 (65.05V)
        assertEquals(6505, state.voltage, "Voltage should be 6505 (65.05V)")

        // Model should be extracted from name packet
        assertEquals("KS-S18", state.model, "Model should be KS-S18")

        // Name should include version suffix
        assertTrue(state.name.startsWith("KS-S18"), "Name should start with KS-S18")

        // Wheel type should be preserved
        assertEquals(WheelType.KINGSONG, state.wheelType)

        manager.disconnect()
    }

    @Test
    fun `real Kingsong packets produce correct speed and temperature`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)

        val state = manager.wheelState.value

        // Speed from live data packet
        assertTrue(state.speed > 0, "Speed should be positive after live data")

        // Temperature from live data packet
        assertTrue(state.temperature > 0, "Temperature should be set from live data")

        manager.disconnect()
    }

    // ==================== connectionState transitions ====================

    @Test
    fun `connectionState transitions to Connected after isReady`() = runTest {
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

        manager.disconnect()
    }

    @Test
    fun `connectionState not Connected until decoder has both model and voltage`() = runTest {
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

        manager.disconnect()
    }

    // ==================== Partial/incomplete data ====================

    @Test
    fun `partial data does not crash and state unchanged`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Set initial state with a valid packet
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)
        val stateAfterValid = manager.wheelState.value

        // Feed incomplete frame (too short for Kingsong 20-byte frame)
        manager.onDataReceived(byteArrayOf(0xAA.toByte(), 0x55))

        // State should be unchanged
        assertEquals(stateAfterValid.voltage, manager.wheelState.value.voltage)
        assertEquals(stateAfterValid.model, manager.wheelState.value.model)

        manager.disconnect()
    }

    @Test
    fun `empty data does not crash`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed empty array
        manager.onDataReceived(byteArrayOf())

        // Should still be in default state
        assertEquals(0, manager.wheelState.value.speed)

        manager.disconnect()
    }

    // ==================== consecutiveDecodeErrors ====================

    @Test
    fun `consecutiveDecodeErrors increments on garbage data`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertEquals(0, manager.consecutiveDecodeErrors.value, "Should start at 0")

        // Feed garbage data — decoder returns null
        manager.onDataReceived(byteArrayOf(0x01, 0x02, 0x03))
        assertTrue(manager.consecutiveDecodeErrors.value > 0, "Should increment on garbage")

        // Feed more garbage
        manager.onDataReceived(byteArrayOf(0x04, 0x05, 0x06))
        assertTrue(
            manager.consecutiveDecodeErrors.value >= 2,
            "Should accumulate errors: ${manager.consecutiveDecodeErrors.value}"
        )

        manager.disconnect()
    }

    @Test
    fun `consecutiveDecodeErrors resets on valid decode`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Build up errors with garbage data
        repeat(5) {
            manager.onDataReceived(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        }
        assertTrue(
            manager.consecutiveDecodeErrors.value > 0,
            "Should have accumulated errors"
        )

        // Feed valid packet → resets counter
        manager.onDataReceived(ksNamePacket)

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should reset to 0 after valid decode")

        manager.disconnect()
    }

    @Test
    fun `consecutiveDecodeErrors resets on connect`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Build up errors
        manager.onDataReceived(byteArrayOf(0x01))
        assertTrue(manager.consecutiveDecodeErrors.value > 0)

        // Reconnect
        manager.disconnect()
        manager.connect("AA:BB:CC:DD:EE:FF")

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should reset to 0 on new connect")

        manager.disconnect()
    }

    @Test
    fun `consecutiveDecodeErrors resets on disconnect`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Build up errors
        manager.onDataReceived(byteArrayOf(0x01))
        assertTrue(manager.consecutiveDecodeErrors.value > 0)

        manager.disconnect()

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Should reset to 0 on disconnect")
    }

    // ==================== Granular sub-states ====================

    @Test
    fun `telemetryState updates on live data`() = runTest {
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
        assertTrue(newTelemetry.voltage > 0, "Voltage should be set in telemetry")

        manager.disconnect()
    }

    @Test
    fun `identityState updates on name packet`() = runTest {
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

        manager.disconnect()
    }

    @Test
    fun `identityState wheelType set on decoder setup`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType,
            "Identity should have wheelType after setup")

        manager.disconnect()
    }

    @Test
    fun `telemetryState does not change on name-only packet`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val initialTelemetry = manager.telemetryState.value

        // Name packet changes identity, not telemetry
        manager.onDataReceived(ksNamePacket)

        assertEquals(initialTelemetry, manager.telemetryState.value,
            "TelemetryState should NOT change on name-only packet")

        manager.disconnect()
    }

    // ==================== Multi-packet integration ====================

    @Test
    fun `full packet sequence produces complete state`() = runTest {
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

        val state = manager.wheelState.value

        // After full sequence, should have: model, voltage, speed, battery, distance
        assertTrue(state.model.isNotEmpty(), "Model should be set")
        assertTrue(state.voltage > 0, "Voltage should be set")
        assertTrue(state.batteryLevel > 0, "Battery level should be computed")
        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should be Connected after full sequence"
        )

        manager.disconnect()
    }

    @Test
    fun `recovery from garbage to valid data`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Feed garbage — no crash, errors accumulate
        repeat(3) {
            manager.onDataReceived(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
        }
        val errorsAfterGarbage = manager.consecutiveDecodeErrors.value
        assertTrue(errorsAfterGarbage > 0)

        // Feed valid data — state updates, errors reset
        manager.onDataReceived(ksNamePacket)
        manager.onDataReceived(ksLiveDataPacket)

        assertEquals(0, manager.consecutiveDecodeErrors.value,
            "Errors should reset after valid data")
        assertTrue(manager.wheelState.value.voltage > 0,
            "State should update after recovery")
        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should reach Connected after recovery"
        )

        manager.disconnect()
    }
}
