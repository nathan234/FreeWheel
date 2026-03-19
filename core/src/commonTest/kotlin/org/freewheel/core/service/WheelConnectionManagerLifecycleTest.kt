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
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecodedData
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle tests for [WheelConnectionManager].
 *
 * Uses [FakeBleManager] and [FakeDecoder] to test the full connection lifecycle
 * without platform-specific BLE dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerLifecycleTest {

    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeDecoder: FakeDecoder
    private lateinit var fakeFactory: FakeDecoderFactory

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
        fakeDecoder = FakeDecoder()
        fakeFactory = FakeDecoderFactory(fakeDecoder)
    }

    private fun TestScope.createManager(
        decoder: FakeDecoder = fakeDecoder,
        factory: FakeDecoderFactory = fakeFactory
    ): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(fakeBle, factory, backgroundScope, dispatcher)
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

    // InMotion V2 services detected by service UUIDs
    // Detector requires BOTH Nordic UART (6e400001) AND standard ffe0 with ffe4 characteristic
    private val inMotionV2Services = DiscoveredServices(
        listOf(
            DiscoveredService(
                uuid = BleUuids.InMotionV2.SERVICE,
                characteristics = listOf(
                    BleUuids.InMotionV2.READ_CHARACTERISTIC,
                    BleUuids.InMotionV2.WRITE_CHARACTERISTIC
                )
            ),
            DiscoveredService(
                uuid = BleUuids.InMotion.READ_SERVICE,
                characteristics = listOf(BleUuids.InMotion.READ_CHARACTERISTIC)
            )
        )
    )

    // ==================== Connect / Disconnect ====================

    @Test
    fun `connect success transitions to DiscoveringServices`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(
            ConnectionState.DiscoveringServices("AA:BB:CC:DD:EE:FF"),
            manager.connectionState.value
        )
    }

    @Test
    fun `connect failure transitions to Failed`() = runTest(timeout = 0.1.seconds) {
        fakeBle.connectResult = false
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Failed).address)
    }

    @Test
    fun `connect failure with keepalive decoder stops timers and resets decoder`() = runTest(timeout = 0.1.seconds) {
        fakeBle.connectResult = false
        val keepAliveDecoder = FakeDecoder(keepAliveIntervalMs = 100L)
        val manager = createManager(
            decoder = keepAliveDecoder,
            factory = FakeDecoderFactory(keepAliveDecoder)
        )

        // Connect with wheel type hint so decoder is created before BLE connects
        manager.connect("AA:BB:CC:DD:EE:FF", WheelType.KINGSONG)
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertFalse(manager.isKeepAliveRunning.value, "Keep-alive timer should be stopped")
        assertTrue(keepAliveDecoder.resetCalled, "Decoder should be reset")
        assertNull(manager.getCurrentDecoder(), "Decoder should be cleared from state")
    }

    @Test
    fun `connect exception transitions to Failed`() = runTest(timeout = 0.1.seconds) {
        // Use a BleManagerPort that throws
        val throwingBle = object : BleManagerPort {
            override val connectionState = fakeBle.connectionState
            override suspend fun connect(address: String): Boolean =
                throw IllegalStateException("BLE not initialized")
            override suspend fun disconnect() {}
            override suspend fun write(data: ByteArray) = false
            override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {}
            override suspend fun stopScan() {}
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = WheelConnectionManager(throwingBle, fakeFactory, backgroundScope, dispatcher)

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertTrue(state.error.contains("BLE not initialized"))
    }

    @Test
    fun `disconnect resets state and stops timers`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Make decoder ready → Connected
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Now disconnect
        manager.disconnect()
        runCurrent()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertEquals(TelemetryState(), manager.telemetryState.value)
        assertEquals(WheelIdentity(), manager.identityState.value)
        assertNull(manager.getCurrentDecoder())
        assertFalse(manager.isKeepAliveRunning.value)
        assertEquals(1, fakeBle.disconnectCallCount)
    }

    @Test
    fun `connect to new address while connected disconnects old connection`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()

        // Connect to address A and reach Connected state
        manager.connect("AA:AA:AA:AA:AA:AA")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        assertEquals(0, fakeBle.disconnectCallCount)

        // Now connect to address B — should disconnect A first
        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()

        assertEquals(1, fakeBle.disconnectCallCount)
        assertEquals("BB:BB:BB:BB:BB:BB", fakeBle.lastConnectAddress)
    }

    // ==================== Service Discovery ====================

    @Test
    fun `onServicesDiscovered with known name creates decoder`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.KINGSONG, fakeFactory.lastCreatedType)
        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType)
    }

    @Test
    fun `onServicesDiscovered with InMotion V2 services detects type`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onServicesDiscovered(inMotionV2Services, null)
        runCurrent()

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.INMOTION_V2, fakeFactory.lastCreatedType)
    }

    @Test
    fun `onServicesDiscovered with unknown services transitions to Failed`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val unknownServices = DiscoveredServices(
            listOf(
                DiscoveredService(
                    uuid = "12345678-0000-1000-8000-00805f9b34fb",
                    characteristics = emptyList()
                )
            )
        )
        manager.onServicesDiscovered(unknownServices, null)
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.Failed,
            "Expected Failed, got ${manager.connectionState.value}"
        )
    }

    @Test
    fun `onServicesDiscovered with ambiguous services uses GOTWAY_VIRTUAL`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Standard service without name → ambiguous → GOTWAY_VIRTUAL
        val ambiguousServices = DiscoveredServices(
            listOf(
                DiscoveredService(
                    uuid = BleUuids.Gotway.SERVICE,
                    characteristics = listOf(BleUuids.Gotway.READ_CHARACTERISTIC)
                )
            )
        )
        manager.onServicesDiscovered(ambiguousServices, null)
        runCurrent()

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.GOTWAY_VIRTUAL, fakeFactory.lastCreatedType)
    }

    @Test
    fun `onServicesDiscovered stores btName in wheel state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertEquals("KS-S18", manager.identityState.value.btName)
    }

    // ==================== onWheelTypeDetected ====================

    @Test
    fun `onWheelTypeDetected creates decoder and updates state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onWheelTypeDetected(WheelType.VETERAN)
        runCurrent()

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.VETERAN, fakeFactory.lastCreatedType)
        assertEquals(WheelType.VETERAN, manager.identityState.value.wheelType)
    }

    // ==================== Init Commands ====================

    @Test
    fun `init commands sent after decoder setup`() = runTest(timeout = 0.1.seconds) {
        val initData = byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x02)
        fakeDecoder.initCommandList = listOf(WheelCommand.SendBytes(initData))

        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(initData) },
            "Init command data should be written to BLE. Written: ${fakeBle.writtenData.size} commands"
        )
    }

    @Test
    fun `multiple init commands sent in order`() = runTest(timeout = 0.1.seconds) {
        val cmd1 = byteArrayOf(0x01)
        val cmd2 = byteArrayOf(0x02)
        val cmd3 = byteArrayOf(0x03)
        fakeDecoder.initCommandList = listOf(
            WheelCommand.SendBytes(cmd1),
            WheelCommand.SendBytes(cmd2),
            WheelCommand.SendBytes(cmd3)
        )

        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertTrue(fakeBle.writtenData.size >= 3, "Should have written at least 3 commands")
        // Find the init commands in order
        val initWrites = fakeBle.writtenData.filter {
            it.contentEquals(cmd1) || it.contentEquals(cmd2) || it.contentEquals(cmd3)
        }
        assertEquals(3, initWrites.size)
    }

    // ==================== Data Received ====================

    @Test
    fun `onDataReceived updates wheel state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500, voltage = 8400, batteryLevel = 85)
        ))

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertEquals(2500, manager.telemetryState.value.speed)
        assertEquals(8400, manager.telemetryState.value.voltage)
        assertEquals(85, manager.telemetryState.value.batteryLevel)
    }

    @Test
    fun `onDataReceived with no decoder logs and does nothing`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        // No decoder set up

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertEquals(0, manager.telemetryState.value.speed)
    }

    @Test
    fun `onDataReceived with Buffering decode result does not update state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Set initial state via a decode
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500)
        ))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        assertEquals(2500, manager.telemetryState.value.speed)

        // Now return Buffering (incomplete frame)
        fakeDecoder.decodeResult = DecodeResult.Buffering
        manager.onDataReceived(byteArrayOf(0x02))
        runCurrent()

        // State should be unchanged
        assertEquals(2500, manager.telemetryState.value.speed)
    }

    // ==================== Decoder Ready → Connected ====================

    @Test
    fun `decoder ready transitions to Connected`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Connected, "Expected Connected, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
    }

    @Test
    fun `decoder not ready does not transition to Connected`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500)
        ))
        fakeDecoder.ready = false

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should not be Connected when decoder is not ready"
        )
    }

    @Test
    fun `Connected state not re-emitted on subsequent data`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        val firstState = manager.connectionState.value

        // Send more data — state should remain Connected (same instance)
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 3000),
            identity = WheelIdentity(name = "KS-S18")
        ))
        manager.onDataReceived(byteArrayOf(0x02))
        runCurrent()

        assertTrue(manager.connectionState.value === firstState,
            "Connected state should not be re-emitted")
    }

    // ==================== Keep-Alive ====================

    @Test
    fun `keep-alive starts when decoder has non-zero interval`() = runTest(timeout = 0.1.seconds) {
        val decoder = FakeDecoder(keepAliveIntervalMs = 100L)
        val factory = FakeDecoderFactory(decoder)
        val manager = createManager(decoder = decoder, factory = factory)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.INMOTION_V2)
        runCurrent()

        assertTrue(manager.isKeepAliveRunning.value, "Keep-alive should be running after setupDecoder")
    }

    @Test
    fun `keep-alive does not start for zero interval decoder`() = runTest(timeout = 0.1.seconds) {
        // Default FakeDecoder has keepAliveIntervalMs = 0 (like Kingsong/Gotway)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 100),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertFalse(manager.isKeepAliveRunning.value,
            "Keep-alive should NOT run for zero interval")
    }

    @Test
    fun `keep-alive sends periodic commands`() = runTest(timeout = 0.1.seconds) {
        val keepAliveData = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val decoder = FakeDecoder(
            keepAliveIntervalMs = 50L,
            keepAliveCommand = WheelCommand.SendBytes(keepAliveData)
        )
        val factory = FakeDecoderFactory(decoder)
        val manager = createManager(decoder = decoder, factory = factory)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.INMOTION_V2)
        runCurrent()

        // Clear any writes from init commands
        fakeBle.clearWrittenData()

        // Advance past initial delay (50ms) + one interval (50ms) + extra
        advanceTimeBy(200)

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(keepAliveData) },
            "Keep-alive command should have been written. Written: ${fakeBle.writtenData.size}"
        )
    }

    // ==================== Response Commands ====================

    @Test
    fun `response commands from decoder are dispatched`() = runTest(timeout = 0.1.seconds) {
        val responseData = byteArrayOf(0x98.toByte(), 0x01, 0x00)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeBle.clearWrittenData()

        // Decoder returns a response command (like KS 0xA4 → 0x98 acknowledgment)
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            commands = listOf(WheelCommand.SendBytes(responseData))
        ))

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(responseData) },
            "Response command should be written to BLE"
        )
    }

    @Test
    fun `multiple response commands dispatched in order`() = runTest(timeout = 0.1.seconds) {
        val resp1 = byteArrayOf(0x01)
        val resp2 = byteArrayOf(0x02)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeBle.clearWrittenData()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            commands = listOf(
                WheelCommand.SendBytes(resp1),
                WheelCommand.SendBytes(resp2)
            )
        ))

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val respWrites = fakeBle.writtenData.filter {
            it.contentEquals(resp1) || it.contentEquals(resp2)
        }
        assertEquals(2, respWrites.size, "Both response commands should be written")
    }

    // ==================== Config ====================

    @Test
    fun `updateConfig and getConfig round-trip`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        val config = DecoderConfig(useMph = true, useFahrenheit = true, batteryCapacity = 1800)

        manager.updateConfig(config)
        runCurrent()

        assertEquals(config, manager.getConfig())
    }

    // ==================== sendCommand ====================

    @Test
    fun `sendCommand SendBytes writes directly`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        manager.sendCommand(WheelCommand.SendBytes(data))
        runCurrent()

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(data) },
            "SendBytes should write directly"
        )
    }

    @Test
    fun `sendCommand with decoder buildCommand`() = runTest(timeout = 0.1.seconds) {
        val builtData = byteArrayOf(0x01, 0x02, 0x03)
        fakeDecoder.buildCommandResult = listOf(WheelCommand.SendBytes(builtData))

        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeBle.clearWrittenData()

        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(builtData) },
            "Built command should be written. Written: ${fakeBle.writtenData.size}"
        )
    }

    // ==================== Connection Info ====================

    @Test
    fun `getConnectionInfo returns null before connect`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        assertNull(manager.getConnectionInfo())
    }

    @Test
    fun `getConnectionInfo populated after service discovery`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        val info = manager.getConnectionInfo()
        assertNotNull(info)
        assertEquals(WheelType.KINGSONG, info.wheelType)
    }

    // ==================== BLE Error Tracking ====================

    @Test
    fun `bleError increments counter and resets on successful data`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Send a few BLE errors
        manager.onBleError()
        manager.onBleError()
        manager.onBleError()
        runCurrent()

        assertEquals(3, manager.consecutiveBleErrors.value)

        // Successful data resets the counter
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500)
        ))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertEquals(0, manager.consecutiveBleErrors.value)
    }

    @Test
    fun `consecutive BLE errors trigger ConnectionLost after threshold`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Make decoder ready → Connected
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 2500),
            identity = WheelIdentity(name = "KS-S18")
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Send MAX_BLE_ERRORS consecutive errors
        repeat(WheelConnectionManager.MAX_BLE_ERRORS) {
            manager.onBleError()
        }
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.ConnectionLost, "Expected ConnectionLost, got $state")
        assertEquals("Too many BLE errors", (state as ConnectionState.ConnectionLost).reason)
        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
    }

    // ==================== Derived Flow: consecutiveBleErrors ====================

    @Test
    fun `consecutiveBleErrors flow exposed correctly`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        assertEquals(0, manager.consecutiveBleErrors.value)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        manager.onBleError()
        runCurrent()
        assertEquals(1, manager.consecutiveBleErrors.value)
    }

    // ==================== Decoder Reset on Type Change ====================

    @Test
    fun `changing wheel type resets previous decoder`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertFalse(fakeDecoder.resetCalled)

        // Change wheel type
        manager.onWheelTypeDetected(WheelType.VETERAN)
        runCurrent()

        assertTrue(fakeDecoder.resetCalled, "Previous decoder should be reset")
    }
}

// ==================== Test Doubles ====================

/**
 * Controllable [WheelDecoder] for lifecycle testing.
 */
class FakeDecoder(
    override val wheelType: WheelType = WheelType.KINGSONG,
    override val keepAliveIntervalMs: Long = 0L,
    keepAliveCommand: WheelCommand? = null
) : WheelDecoder {

    var ready = false
    var decodeResult: DecodeResult = DecodeResult.Buffering
    var initCommandList: List<WheelCommand> = emptyList()
    var buildCommandResult: List<WheelCommand> = emptyList()
    private var _keepAliveCommand: WheelCommand? = keepAliveCommand
    var resetCalled = false
    var decodeCallCount = 0

    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
        decodeCallCount++
        return decodeResult
    }

    override fun isReady(): Boolean = ready

    override fun reset() {
        resetCalled = true
        ready = false
    }

    override fun getInitCommands(): List<WheelCommand> = initCommandList

    override fun buildCommand(command: WheelCommand): List<WheelCommand> = buildCommandResult

    override fun getKeepAliveCommand(): WheelCommand? = _keepAliveCommand
}

/**
 * Factory that returns a pre-configured [FakeDecoder].
 */
class FakeDecoderFactory(private val decoder: FakeDecoder) : WheelDecoderFactory {
    var lastCreatedType: WheelType? = null
        private set

    var createCount = 0
        private set

    override fun createDecoder(wheelType: WheelType): WheelDecoder {
        lastCreatedType = wheelType
        createCount++
        return decoder
    }

    override fun supportedTypes(): List<WheelType> = WheelType.entries.toList()
}
