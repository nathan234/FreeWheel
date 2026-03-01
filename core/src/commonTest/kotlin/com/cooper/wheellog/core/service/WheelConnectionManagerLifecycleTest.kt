package com.cooper.wheellog.core.service

import com.cooper.wheellog.core.ble.BleUuids
import com.cooper.wheellog.core.ble.DiscoveredService
import com.cooper.wheellog.core.ble.DiscoveredServices
import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.protocol.DecodedData
import com.cooper.wheellog.core.protocol.DecoderConfig
import com.cooper.wheellog.core.protocol.WheelCommand
import com.cooper.wheellog.core.protocol.WheelDecoder
import com.cooper.wheellog.core.protocol.WheelDecoderFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
// advanceUntilIdle removed — DataTimeoutTracker uses real clock (currentTimeMillis)
// which never triggers under virtual time, causing advanceUntilIdle to loop forever.
// Use runCurrent() instead: with UnconfinedTestDispatcher, launched coroutines run
// eagerly so runCurrent() is sufficient to process init/response commands.
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        return WheelConnectionManager(fakeBle, factory, this, dispatcher)
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
    fun `connect success transitions to DiscoveringServices`() = runTest {
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")

        assertEquals(
            ConnectionState.DiscoveringServices("AA:BB:CC:DD:EE:FF"),
            manager.connectionState.value
        )

        manager.disconnect()
    }

    @Test
    fun `connect failure transitions to Failed`() = runTest {
        fakeBle.connectResult = false
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Failed).address)
    }

    @Test
    fun `connect exception transitions to Failed`() = runTest {
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
        val manager = WheelConnectionManager(throwingBle, fakeFactory, this, dispatcher)

        manager.connect("AA:BB:CC:DD:EE:FF")

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertTrue((state as ConnectionState.Failed).error.contains("BLE not initialized"))
    }

    @Test
    fun `disconnect resets state and stops timers`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Make decoder ready → Connected
        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500, name = "KS-S18")
        )
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))

        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Now disconnect
        manager.disconnect()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertEquals(WheelState(), manager.wheelState.value)
        assertNull(manager.getCurrentDecoder())
        assertFalse(manager.isKeepAliveRunning.value)
        assertEquals(1, fakeBle.disconnectCallCount)
    }

    // ==================== Service Discovery ====================

    @Test
    fun `onServicesDiscovered with known name creates decoder`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        manager.onServicesDiscovered(kingsongServices, "KS-S18")

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.KINGSONG, fakeFactory.lastCreatedType)
        assertEquals(WheelType.KINGSONG, manager.wheelState.value.wheelType)

        manager.disconnect()
    }

    @Test
    fun `onServicesDiscovered with InMotion V2 services detects type`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        manager.onServicesDiscovered(inMotionV2Services, null)

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.INMOTION_V2, fakeFactory.lastCreatedType)

        manager.disconnect()
    }

    @Test
    fun `onServicesDiscovered with unknown services transitions to Failed`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        val unknownServices = DiscoveredServices(
            listOf(
                DiscoveredService(
                    uuid = "12345678-0000-1000-8000-00805f9b34fb",
                    characteristics = emptyList()
                )
            )
        )
        manager.onServicesDiscovered(unknownServices, null)

        assertTrue(
            manager.connectionState.value is ConnectionState.Failed,
            "Expected Failed, got ${manager.connectionState.value}"
        )

        manager.disconnect()
    }

    @Test
    fun `onServicesDiscovered with ambiguous services uses GOTWAY_VIRTUAL`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

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

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.GOTWAY_VIRTUAL, fakeFactory.lastCreatedType)

        manager.disconnect()
    }

    @Test
    fun `onServicesDiscovered stores btName in wheel state`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        manager.onServicesDiscovered(kingsongServices, "KS-S18")

        assertEquals("KS-S18", manager.wheelState.value.btName)

        manager.disconnect()
    }

    // ==================== onWheelTypeDetected ====================

    @Test
    fun `onWheelTypeDetected creates decoder and updates state`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        manager.onWheelTypeDetected(WheelType.VETERAN)

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.VETERAN, fakeFactory.lastCreatedType)
        assertEquals(WheelType.VETERAN, manager.wheelState.value.wheelType)

        manager.disconnect()
    }

    // ==================== Init Commands ====================

    @Test
    fun `init commands sent after decoder setup`() = runTest {
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

        manager.disconnect()
    }

    @Test
    fun `multiple init commands sent in order`() = runTest {
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

        manager.disconnect()
    }

    // ==================== Data Received ====================

    @Test
    fun `onDataReceived updates wheel state`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500, voltage = 8400, batteryLevel = 85)
        )

        manager.onDataReceived(byteArrayOf(0x01))

        assertEquals(2500, manager.wheelState.value.speed)
        assertEquals(8400, manager.wheelState.value.voltage)
        assertEquals(85, manager.wheelState.value.batteryLevel)

        manager.disconnect()
    }

    @Test
    fun `onDataReceived with no decoder logs and does nothing`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        // No decoder set up

        manager.onDataReceived(byteArrayOf(0x01))

        assertEquals(0, manager.wheelState.value.speed)

        manager.disconnect()
    }

    @Test
    fun `onDataReceived with null decode result does not update state`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Set initial state via a decode
        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500)
        )
        manager.onDataReceived(byteArrayOf(0x01))
        assertEquals(2500, manager.wheelState.value.speed)

        // Now return null (incomplete frame)
        fakeDecoder.decodeResult = null
        manager.onDataReceived(byteArrayOf(0x02))

        // State should be unchanged
        assertEquals(2500, manager.wheelState.value.speed)

        manager.disconnect()
    }

    // ==================== Decoder Ready → Connected ====================

    @Test
    fun `decoder ready transitions to Connected`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500, name = "KS-S18")
        )
        fakeDecoder.ready = true

        manager.onDataReceived(byteArrayOf(0x01))

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Connected, "Expected Connected, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Connected).address)

        manager.disconnect()
    }

    @Test
    fun `decoder not ready does not transition to Connected`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500)
        )
        fakeDecoder.ready = false

        manager.onDataReceived(byteArrayOf(0x01))

        assertFalse(
            manager.connectionState.value is ConnectionState.Connected,
            "Should not be Connected when decoder is not ready"
        )

        manager.disconnect()
    }

    @Test
    fun `Connected state not re-emitted on subsequent data`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500, name = "KS-S18")
        )
        fakeDecoder.ready = true

        manager.onDataReceived(byteArrayOf(0x01))
        val firstState = manager.connectionState.value

        // Send more data — state should remain Connected (same instance)
        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 3000, name = "KS-S18")
        )
        manager.onDataReceived(byteArrayOf(0x02))

        assertTrue(manager.connectionState.value === firstState,
            "Connected state should not be re-emitted")

        manager.disconnect()
    }

    // ==================== Keep-Alive ====================

    @Test
    fun `keep-alive starts when decoder has non-zero interval`() = runTest {
        val decoder = FakeDecoder(keepAliveIntervalMs = 100L)
        val factory = FakeDecoderFactory(decoder)
        val manager = createManager(decoder = decoder, factory = factory)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.INMOTION_V2)
        // Keep-alive starts immediately in setupDecoder for polling decoders
        runCurrent()

        assertTrue(manager.isKeepAliveRunning.value, "Keep-alive should be running after setupDecoder")

        // Clean up timer coroutine
        manager.disconnect()
    }

    @Test
    fun `keep-alive does not start for zero interval decoder`() = runTest {
        // Default FakeDecoder has keepAliveIntervalMs = 0 (like Kingsong/Gotway)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 100, name = "KS-S18")
        )
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))

        assertFalse(manager.isKeepAliveRunning.value,
            "Keep-alive should NOT run for zero interval")

        manager.disconnect()
    }

    @Test
    fun `keep-alive sends periodic commands`() = runTest {
        val keepAliveData = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val decoder = FakeDecoder(
            keepAliveIntervalMs = 50L,
            keepAliveCommand = WheelCommand.SendBytes(keepAliveData)
        )
        val factory = FakeDecoderFactory(decoder)
        val manager = createManager(decoder = decoder, factory = factory)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.INMOTION_V2)
        // Keep-alive timer starts immediately in setupDecoder; process launches without
        // advancing time (avoids infinite timer loop in advanceUntilIdle)
        runCurrent()

        // Clear any writes from init commands
        fakeBle.clearWrittenData()

        // Advance past initial delay (50ms) + one interval (50ms) + extra
        advanceTimeBy(200)

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(keepAliveData) },
            "Keep-alive command should have been written. Written: ${fakeBle.writtenData.size}"
        )

        // Clean up timer coroutine
        manager.disconnect()
    }

    // ==================== Response Commands ====================

    @Test
    fun `response commands from decoder are dispatched`() = runTest {
        val responseData = byteArrayOf(0x98.toByte(), 0x01, 0x00)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeBle.clearWrittenData()

        // Decoder returns a response command (like KS 0xA4 → 0x98 acknowledgment)
        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500),
            commands = listOf(WheelCommand.SendBytes(responseData))
        )

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(responseData) },
            "Response command should be written to BLE"
        )

        manager.disconnect()
    }

    @Test
    fun `multiple response commands dispatched in order`() = runTest {
        val resp1 = byteArrayOf(0x01)
        val resp2 = byteArrayOf(0x02)
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeBle.clearWrittenData()

        fakeDecoder.decodeResult = DecodedData(
            newState = WheelState(speed = 2500),
            commands = listOf(
                WheelCommand.SendBytes(resp1),
                WheelCommand.SendBytes(resp2)
            )
        )

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val respWrites = fakeBle.writtenData.filter {
            it.contentEquals(resp1) || it.contentEquals(resp2)
        }
        assertEquals(2, respWrites.size, "Both response commands should be written")

        manager.disconnect()
    }

    // ==================== Config ====================

    @Test
    fun `updateConfig and getConfig round-trip`() = runTest {
        val manager = createManager()
        val config = DecoderConfig(useMph = true, useFahrenheit = true, batteryCapacity = 1800)

        manager.updateConfig(config)

        assertEquals(config, manager.getConfig())
    }

    // ==================== sendCommand ====================

    @Test
    fun `sendCommand SendBytes writes directly`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")

        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        manager.sendCommand(WheelCommand.SendBytes(data))

        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(data) },
            "SendBytes should write directly"
        )

        manager.disconnect()
    }

    @Test
    fun `sendCommand with decoder buildCommand`() = runTest {
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

        manager.disconnect()
    }

    // ==================== Connection Info ====================

    @Test
    fun `getConnectionInfo returns null before connect`() = runTest {
        val manager = createManager()
        assertNull(manager.getConnectionInfo())
    }

    @Test
    fun `getConnectionInfo populated after service discovery`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")

        val info = manager.getConnectionInfo()
        assertNotNull(info)
        assertEquals(WheelType.KINGSONG, info.wheelType)

        manager.disconnect()
    }

    // ==================== Decoder Reset on Type Change ====================

    @Test
    fun `changing wheel type resets previous decoder`() = runTest {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        assertFalse(fakeDecoder.resetCalled)

        // Change wheel type
        manager.onWheelTypeDetected(WheelType.VETERAN)
        runCurrent()

        assertTrue(fakeDecoder.resetCalled, "Previous decoder should be reset")

        manager.disconnect()
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
    var decodeResult: DecodedData? = null
    var initCommandList: List<WheelCommand> = emptyList()
    var buildCommandResult: List<WheelCommand> = emptyList()
    private var _keepAliveCommand: WheelCommand? = keepAliveCommand
    var resetCalled = false
    var decodeCallCount = 0

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
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
