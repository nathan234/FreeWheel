package org.freewheel.core.charger

import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.FakeBleManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChargerConnectionManagerTest {

    private lateinit var fakeBle: FakeBleManager

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
    }

    private fun TestScope.createManager(): ChargerConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ChargerConnectionManager(fakeBle, backgroundScope, dispatcher)
    }

    // ── Helper: simulate connect + services + auth response ────────

    private fun TestScope.connectAndAuth(
        manager: ChargerConnectionManager,
        address: String = "AA:BB:CC:DD:EE:FF",
        password: String = "1234"
    ) {
        manager.connect(address, password)
        runCurrent()

        // Simulate BLE connect success → services discovered → auth response
        manager.onServicesDiscovered()
        runCurrent()

        // Feed auth success response: {3, 2, 1, checksum}
        val authResponse = byteArrayOf(3, 2, 1, 3)
        manager.onDataReceived(authResponse)
        runCurrent()
    }

    // ── Helper: build a Status frame ───────────────────────────────

    private fun buildStatusFrame(
        dcVoltage: Float = 84f,
        dcCurrent: Float = 5f,
        acVoltage: Float = 230f,
        acCurrent: Float = 2f,
        outputEnabled: Boolean = true
    ): ByteArray {
        val frame = ByteArray(49)
        frame[0] = 48
        frame[1] = 6
        HwChargerProtocol.encodeFloat(acVoltage).copyInto(frame, 2)
        HwChargerProtocol.encodeFloat(acCurrent).copyInto(frame, 6)
        HwChargerProtocol.encodeFloat(50f).copyInto(frame, 10) // acFrequency
        HwChargerProtocol.encodeFloat(35f).copyInto(frame, 14) // temp1
        HwChargerProtocol.encodeFloat(38f).copyInto(frame, 18) // temp2
        HwChargerProtocol.encodeFloat(dcVoltage).copyInto(frame, 22)
        HwChargerProtocol.encodeFloat(dcCurrent).copyInto(frame, 26)
        HwChargerProtocol.encodeFloat(15f).copyInto(frame, 30) // currentLimit
        HwChargerProtocol.encodeFloat(92f).copyInto(frame, 34) // efficiency
        frame[38] = if (outputEnabled) 0 else 1
        frame[48] = HwChargerProtocol.checksum(frame)
        return frame
    }

    // ── Connection lifecycle ───────────────────────────────────────

    @Test
    fun connect_transitionsToConnectingOrDiscovering() = runTest(timeout = 1.seconds) {
        // With UnconfinedTestDispatcher, BLE connect returns immediately,
        // so state may already be DiscoveringServices by the time we check
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(
            state is ConnectionState.Connecting || state is ConnectionState.DiscoveringServices,
            "Expected Connecting or DiscoveringServices, got $state"
        )
    }

    @Test
    fun connect_callsBleConnect() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        assertEquals("AA:BB:CC:DD:EE:FF", fakeBle.lastConnectAddress)
    }

    @Test
    fun connect_bleSuccess_transitionsToDiscoveringServices() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        // FakeBleManager returns success by default, so BleConnectResult(true) fires
        assertTrue(manager.connectionState.value is ConnectionState.DiscoveringServices)
    }

    @Test
    fun servicesDiscovered_configuresBle() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        manager.onServicesDiscovered()
        runCurrent()

        assertNotNull(fakeBle.lastConfigureForWheel)
        assertEquals(
            HwChargerProtocol.SERVICE_UUID,
            fakeBle.lastConfigureForWheel!![0]
        )
        assertEquals(
            HwChargerProtocol.CHARACTERISTIC_UUID,
            fakeBle.lastConfigureForWheel!![1]
        )
    }

    @Test
    fun servicesDiscovered_sendsAuthCommand() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        manager.onServicesDiscovered()
        runCurrent()
        // Advance past inter-chunk delay (25ms) so second chunk is written
        advanceTimeBy(50)
        runCurrent()

        // Auth frame should have been written (chunked: 36 bytes > 20 byte MTU)
        assertTrue(fakeBle.writtenData.isNotEmpty(), "Auth frame should have been sent")
        // Total bytes written = 36 (in 2 chunks: 20 + 16)
        val totalBytes = fakeBle.writtenData.sumOf { it.size }
        assertEquals(36, totalBytes, "Auth frame should be 36 bytes total")
    }

    @Test
    fun authSuccess_transitionsToConnected() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        assertTrue(manager.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun authFailure_staysDiscoveringServices() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "wrong")
        runCurrent()
        manager.onServicesDiscovered()
        runCurrent()

        // Auth failure: byte[2] = 0
        val authFail = byteArrayOf(3, 2, 0, 2)
        manager.onDataReceived(authFail)
        runCurrent()

        // Should not transition to Connected
        assertFalse(manager.connectionState.value is ConnectionState.Connected)
        assertFalse(manager.chargerState.value.isAuthenticated)
    }

    @Test
    fun disconnect_resetsState() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        manager.disconnect()
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Disconnected)
        assertEquals(ChargerState(), manager.chargerState.value)
    }

    @Test
    fun bleFailed_transitionsToFailed() = runTest(timeout = 1.seconds) {
        fakeBle.connectResult = false
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", "1234")
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Failed)
    }

    // ── Data decoding ──────────────────────────────────────────────

    @Test
    fun statusFrame_updatesChargerState() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)
        fakeBle.clearWrittenData()

        val statusFrame = buildStatusFrame(dcVoltage = 84.5f, dcCurrent = 5.2f)
        manager.onDataReceived(statusFrame)
        runCurrent()

        val state = manager.chargerState.value
        assertEquals(84.5f, state.dcVoltage)
        assertEquals(5.2f, state.dcCurrent)
        assertTrue(state.isCharging)
        assertTrue(state.isOutputEnabled)
    }

    @Test
    fun multipleStatusFrames_updatesIncrementally() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        manager.onDataReceived(buildStatusFrame(dcVoltage = 84f))
        runCurrent()
        assertEquals(84f, manager.chargerState.value.dcVoltage)

        manager.onDataReceived(buildStatusFrame(dcVoltage = 83.5f))
        runCurrent()
        assertEquals(83.5f, manager.chargerState.value.dcVoltage)
    }

    // ── Command sending ────────────────────────────────────────────

    @Test
    fun setOutputVoltage_sendsCorrectFrame() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)
        fakeBle.clearWrittenData()

        manager.setOutputVoltage(84.0f)
        runCurrent()

        assertTrue(fakeBle.writtenData.isNotEmpty())
        val frame = fakeBle.writtenData[0]
        assertEquals(0x07, frame[1].toInt() and 0xFF) // CMD_SET_VOLTAGE
        assertEquals(84.0f, HwChargerProtocol.decodeFloat(frame, 2))
    }

    @Test
    fun setOutputCurrent_sendsCorrectFrame() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)
        fakeBle.clearWrittenData()

        manager.setOutputCurrent(10.0f)
        runCurrent()

        assertTrue(fakeBle.writtenData.isNotEmpty())
        val frame = fakeBle.writtenData[0]
        assertEquals(0x08, frame[1].toInt() and 0xFF) // CMD_SET_CURRENT
    }

    @Test
    fun toggleOutput_enable_sendsInvertedLogic() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)
        fakeBle.clearWrittenData()

        manager.toggleOutput(true)
        runCurrent()

        assertTrue(fakeBle.writtenData.isNotEmpty())
        val frame = fakeBle.writtenData[0]
        assertEquals(0x0C, frame[1].toInt() and 0xFF) // CMD_START_STOP
        assertEquals(0, frame[2].toInt() and 0xFF) // 0 = enable (inverted)
    }

    @Test
    fun toggleOutput_disable_sendsInvertedLogic() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)
        fakeBle.clearWrittenData()

        manager.toggleOutput(false)
        runCurrent()

        val frame = fakeBle.writtenData[0]
        assertEquals(1, frame[2].toInt() and 0xFF) // 1 = disable (inverted)
    }

    // ── BLE errors ─────────────────────────────────────────────────

    @Test
    fun bleErrors_belowThreshold_staysConnected() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        repeat(ChargerConnectionManager.MAX_BLE_ERRORS - 1) {
            manager.onBleError()
        }
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun bleErrors_atThreshold_connectionLost() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        repeat(ChargerConnectionManager.MAX_BLE_ERRORS) {
            manager.onBleError()
        }
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.ConnectionLost)
    }

    @Test
    fun bleErrors_resetOnSuccessfulDecode() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        // Accumulate some errors
        repeat(ChargerConnectionManager.MAX_BLE_ERRORS - 1) {
            manager.onBleError()
        }
        runCurrent()

        // Successful data resets the counter
        manager.onDataReceived(buildStatusFrame())
        runCurrent()

        // Now more errors should start from 0
        repeat(ChargerConnectionManager.MAX_BLE_ERRORS - 1) {
            manager.onBleError()
        }
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Connected)
    }

    // ── Shutdown ───────────────────────────────────────────────────

    @Test
    fun shutdown_disconnectsAndStops() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        connectAndAuth(manager)

        manager.shutdown()

        assertTrue(manager.connectionState.value is ConnectionState.Disconnected)
    }
}
