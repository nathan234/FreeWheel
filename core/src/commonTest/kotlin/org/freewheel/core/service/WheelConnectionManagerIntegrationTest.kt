package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.BleUuids
import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.buildKsAlertFrame
import org.freewheel.core.protocol.buildKsBmsCellFrame
import org.freewheel.core.protocol.buildKsBmsInfoFrame
import org.freewheel.core.protocol.buildKsDistancePacket
import org.freewheel.core.protocol.buildKsLivePacket
import org.freewheel.core.protocol.buildKsNamePacket
import org.freewheel.core.protocol.buildKsSerialPacket
import org.freewheel.core.protocol.shortToBytesBE
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that wire real decoders through the full WheelConnectionManager pipeline.
 *
 * Unlike [WheelConnectionManagerLifecycleTest] (which uses FakeDecoder), these tests use
 * [DefaultWheelDecoderFactory] to create real [KingsongDecoder] / [GotwayDecoder] instances.
 * This catches bugs in: frame routing, state accumulation across multiple frame types,
 * init command dispatch timing, response command propagation, and isReady() → Connected
 * transition logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerIntegrationTest {

    private lateinit var fakeBle: FakeBleManager

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
    }

    private fun TestScope.createManager(): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(
            fakeBle,
            DefaultWheelDecoderFactory(),
            backgroundScope,
            dispatcher
        )
    }

    // Kingsong services detected by device name
    private val kingsongServices = DiscoveredServices(
        listOf(
            DiscoveredService(
                uuid = BleUuids.Kingsong.SERVICE,
                characteristics = listOf(BleUuids.Kingsong.READ_CHARACTERISTIC)
            )
        )
    )

    // ==================== Kingsong Connection Helper ====================

    /**
     * Connect and set up a Kingsong decoder via service discovery.
     * Returns the manager in DiscoveringServices state with a real KingsongDecoder.
     */
    private fun TestScope.connectKingsong(): WheelConnectionManager {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()  // BLE connect
        runCurrent()  // BleConnectResult → DiscoveringServices
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()  // Decoder created, init commands dispatched
        return manager
    }

    /**
     * Connect via onWheelTypeDetected (for Gotway and other types).
     */
    private fun TestScope.connectWithType(wheelType: WheelType): WheelConnectionManager {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        runCurrent()
        manager.onWheelTypeDetected(wheelType)
        runCurrent()
        return manager
    }

    // ==================== Kingsong Tests ====================

    @Test
    fun `A1 kingsong connect to ready via name and live data`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Feed name frame → decoder learns model
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()

        // Not yet Connected — hasReceivedVoltage is still false
        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should not be Connected without voltage"
        )

        // Feed live data frame with voltage → triggers isReady
        manager.onDataReceived(buildKsLivePacket(voltage = 6505, speed = 1500))
        runCurrent()

        // Now should be Connected
        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Connected, "Expected Connected, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
        assertEquals("KS-S18", manager.identityState.value.model)
        assertEquals(6505, manager.telemetryState.value!!.voltage)
        assertEquals(1500, manager.telemetryState.value!!.speed)
    }

    @Test
    fun `A2 kingsong init commands written to BLE`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Advance past all SendDelayed commands (cumulative delays: 100+200+300+400 = 1000ms)
        advanceTimeBy(1100)
        runCurrent()

        // KS init commands: 0x9B (REQUEST_NAME), 0x63 (REQUEST_SERIAL),
        // 0x98 (REQUEST_ALARMS), 0x5B (REQUEST_LIGHT_STATUS), 0x81 (REQUEST_LIFT_SENSOR)
        val expectedFrameTypes = listOf(0x9B, 0x63, 0x98, 0x5B, 0x81)

        for (expectedType in expectedFrameTypes) {
            assertTrue(
                fakeBle.writtenData.any { frame ->
                    frame.size == 20 &&
                        (frame[0].toInt() and 0xFF) == 0xAA &&
                        (frame[1].toInt() and 0xFF) == 0x55 &&
                        (frame[16].toInt() and 0xFF) == expectedType
                },
                "Init command 0x${expectedType.toString(16)} should be written to BLE. " +
                    "Written frame types: ${fakeBle.writtenData.filter { it.size == 20 }.map { "0x${(it[16].toInt() and 0xFF).toString(16)}" }}"
            )
        }
    }

    @Test
    fun `A3 kingsong multi-frame state accumulation`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Feed name → model + version
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()

        // Feed live data → voltage, speed, temperature
        manager.onDataReceived(buildKsLivePacket(voltage = 8400, speed = 2500, temperature = 4200))
        runCurrent()

        // Feed serial number
        manager.onDataReceived(buildKsSerialPacket("KS1234567890"))
        runCurrent()

        // Feed distance frame → wheelDistance, temperature2
        manager.onDataReceived(buildKsDistancePacket(distance = 50000, temperature2 = 3800))
        runCurrent()

        val tel = manager.telemetryState.value!!
        val id = manager.identityState.value
        assertEquals("KS-S18", id.model)
        assertEquals(8400, tel.voltage)
        assertEquals(2500, tel.speed)
        assertEquals(4200, tel.temperature)
        assertTrue(id.serialNumber.isNotEmpty(), "Serial number should be populated")
        assertEquals(50000, tel.wheelDistance)
        assertEquals(3800, tel.temperature2)
    }

    @Test
    fun `A4 kingsong 0xA4 alert frame generates 0x98 acknowledgment`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Make decoder ready
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()
        manager.onDataReceived(buildKsLivePacket(voltage = 8400))
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Let init commands finish (serial queue: delays of 100+200+300+400ms)
        advanceTimeBy(1100)
        runCurrent()

        // Clear writes from init commands
        fakeBle.clearWrittenData()

        // Feed 0xA4 alert frame
        manager.onDataReceived(buildKsAlertFrame(
            alarm1Speed = 3000,
            alarm2Speed = 3500,
            alarm3Speed = 4000,
            maxSpeed = 5000
        ))
        runCurrent()

        // Should have written a 0x98 response
        assertTrue(
            fakeBle.writtenData.any { frame ->
                frame.size == 20 &&
                    (frame[16].toInt() and 0xFF) == 0x98
            },
            "0xA4 alert frame should trigger 0x98 acknowledgment. " +
                "Written: ${fakeBle.writtenData.size} frames"
        )
    }

    @Test
    fun `A5 kingsong isReady requires both name and voltage`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Feed live data with voltage=0 → hasReceivedVoltage stays false
        manager.onDataReceived(buildKsLivePacket(voltage = 0))
        runCurrent()

        // Feed name
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()

        // model is set but hasReceivedVoltage is false → NOT Connected
        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should NOT be Connected when voltage has not been received"
        )

        // Feed live data with real voltage → now isReady
        manager.onDataReceived(buildKsLivePacket(voltage = 8400))
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Should be Connected after receiving both name and voltage"
        )
    }

    @Test
    fun `A6 kingsong BMS data accumulates across frames`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Make decoder ready
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()
        manager.onDataReceived(buildKsLivePacket(voltage = 8400))
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Feed BMS info frame (pNum=0x00)
        manager.onDataReceived(buildKsBmsInfoFrame(voltage = 8350, current = 150, remCap = 200, factoryCap = 300))
        runCurrent()

        // Feed BMS cell voltage frames (pNum=0x02, 0x03)
        manager.onDataReceived(buildKsBmsCellFrame(0x02, listOf(4200, 4190, 4180, 4170, 4160, 4150, 4140)))
        runCurrent()
        manager.onDataReceived(buildKsBmsCellFrame(0x03, listOf(4130, 4120, 4110, 4100, 4090, 4080, 4070)))
        runCurrent()

        val bms1 = manager.bmsState.value.bms1
        assertTrue(bms1 != null, "BMS1 should be populated after BMS frames")
        // BMS info should be populated
        assertEquals(83.5, bms1!!.voltage, 0.01)
        assertEquals(1.5, bms1.current, 0.01)
        assertEquals(2000, bms1.remCap)
        assertEquals(3000, bms1.factoryCap)

        // Cell voltages should be populated (7 cells from pNum=0x02 + 7 from pNum=0x03)
        assertEquals(4.2, bms1.cells[0], 0.001)
        assertEquals(4.19, bms1.cells[1], 0.001)
        assertEquals(4.13, bms1.cells[7], 0.001)  // First cell from pNum=0x03
    }

    @Test
    fun `A7 kingsong disconnect resets real decoder state`() = runTest(timeout = 5.seconds) {
        val manager = connectKingsong()

        // Full session: name + live data → Connected
        manager.onDataReceived(buildKsNamePacket("KS-S18-0205"))
        runCurrent()
        manager.onDataReceived(buildKsLivePacket(voltage = 8400, speed = 2500))
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        assertEquals(2500, manager.telemetryState.value!!.speed)

        // Disconnect
        manager.disconnect()
        runCurrent()

        // Verify cleanup
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertNull(manager.telemetryState.value, "Telemetry should be null after disconnect")
        assertEquals(WheelIdentity(), manager.identityState.value)
        assertNull(manager.getCurrentDecoder())
    }

    // ==================== Gotway Tests ====================

    @Test
    fun `B1 gotway live data decode through pipeline`() = runTest(timeout = 5.seconds) {
        val manager = connectWithType(WheelType.GOTWAY)
        // Pin the 16S voltage profile (1.0 scaler) so this test asserts the
        // round-trip independent of whatever default the data class carries.
        manager.updateConfig(DecoderConfig(gotwayVoltage = 0))

        // First send firmware string to make decoder ready
        manager.onDataReceived("GW1.23".encodeToByteArray())
        runCurrent()

        // Feed a live data frame through the unpacker
        manager.onDataReceived(buildGotwayLiveDataFrame(voltage = 6000, speed = 100))
        runCurrent()

        val tel = manager.telemetryState.value!!
        // GotwayDecoder: voltage is passed through scaleVoltage (16S profile = 1.0)
        assertEquals(6000, tel.voltage)
        // GotwayDecoder: speed = signedShort * 3.6, with gotwayNegative=0 → abs()
        // speed = abs(100 * 3.6) = 360
        assertEquals(360, tel.speed)
    }

    @Test
    fun `B2 gotway settings frame populates settings`() = runTest(timeout = 5.seconds) {
        val manager = connectWithType(WheelType.GOTWAY)

        // Initialize decoder with firmware
        manager.onDataReceived("GW1.23".encodeToByteArray())
        runCurrent()

        // Need live data first (unpacker state)
        manager.onDataReceived(buildGotwayLiveDataFrame(voltage = 6000))
        runCurrent()

        // Feed settings frame (frame type 0x04)
        manager.onDataReceived(buildGotwaySettingsFrame(
            pedalsMode = 1,
            tiltBackSpeed = 45,
            ledMode = 3,
            lightMode = 1
        ))
        runCurrent()

        val settings = manager.settingsState.value
        assertTrue(settings is WheelSettings.Begode, "Expected Begode settings, got $settings")
        val begode = settings as WheelSettings.Begode
        // Gotway pedalsMode is decoded as: 2 - pedalsMode from frame
        assertEquals(1, begode.pedalsMode)
        assertEquals(45, begode.tiltBackSpeed)
        assertEquals(3, begode.ledMode)
        assertEquals(1, begode.lightMode)
    }

    @Test
    fun `B3 gotway becomes ready after repeated live data (fallback path)`() = runTest(timeout = 5.seconds) {
        val manager = connectWithType(WheelType.GOTWAY)

        // Do NOT send firmware string — force the fallback path
        // GotwayDecoder.infoAttempt increments on each frame 0x00 when fw/model is empty
        // At MAX_INFO_ATTEMPTS (50), fallback triggers: model="Begode", isReady=true

        // Feed 51 live data frames to trigger the fallback
        // (infoAttempt increments from 0 to 50, at which point fallback fires)
        repeat(51) {
            manager.onDataReceived(buildGotwayLiveDataFrame(voltage = 6000))
            runCurrent()
        }

        val state = manager.connectionState.value
        assertTrue(
            state is ConnectionState.Connected,
            "Expected Connected after fallback, got $state"
        )
        assertEquals("Begode", manager.identityState.value.model)
    }

    // ==================== Gotway Frame Builders ====================

    /**
     * Build a Gotway frame 0x00 (live data).
     * Gotway frames are 24 bytes: 55 AA [18 bytes payload] 5A 5A 5A 5A
     * Byte 18 = frame type, byte 19 = 0x18 (footer byte)
     */
    private fun buildGotwayLiveDataFrame(
        voltage: Int = 6000,
        speed: Int = 0,
        distance: Int = 0,
        beeperVolume: Int = 0
    ): ByteArray {
        val header = byteArrayOf(0x55, 0xAA.toByte())
        return header +
            shortToBytesBE(voltage) +
            shortToBytesBE(speed) +
            byteArrayOf(0, 0) +
            shortToBytesBE(distance) +
            shortToBytesBE(0) + // phaseCurrent
            shortToBytesBE(99) + // temperature (raw MPU value)
            byteArrayOf(0, 0, 0, beeperVolume.toByte(), 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /**
     * Build a Gotway frame 0x04 (settings / total distance).
     *
     * Settings short layout (offset 6-7):
     *   bit 14-13: pedalsMode (0-2, stored as 2 - value)
     *   bit 11-10: speedAlarms
     *   bit 8-7:   rollAngle
     *   bit 0:     inMiles
     */
    private fun buildGotwaySettingsFrame(
        totalDistance: Long = 0,
        inMiles: Boolean = false,
        pedalsMode: Int = 0,
        speedAlarms: Int = 0,
        rollAngle: Int = 0,
        tiltBackSpeed: Int = 0,
        ledMode: Int = 0,
        lightMode: Int = 0
    ): ByteArray {
        val header = byteArrayOf(0x55, 0xAA.toByte())
        val dist = byteArrayOf(
            ((totalDistance shr 24) and 0xFF).toByte(),
            ((totalDistance shr 16) and 0xFF).toByte(),
            ((totalDistance shr 8) and 0xFF).toByte(),
            (totalDistance and 0xFF).toByte()
        )
        val settings = (pedalsMode shl 13) or (speedAlarms shl 10) or
            (rollAngle shl 7) or (if (inMiles) 1 else 0)
        return header +
            dist +
            shortToBytesBE(settings) +
            shortToBytesBE(0) + // power-off timer
            shortToBytesBE(tiltBackSpeed) +
            byteArrayOf(0, ledMode.toByte(), 0, lightMode.toByte(), 0, 0) +
            byteArrayOf(0x04, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }
}
