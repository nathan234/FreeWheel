package org.freewheel.core.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.BleUuids
import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.ble.WheelTopology
import org.freewheel.core.ble.WheelTopologyMatcher
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.ProtocolFamily
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
        factory: FakeDecoderFactory = fakeFactory,
        dataTimeoutTracker: DataTimeoutTracker? = null,
        wheelTypeDetector: WheelTypeDetector = WheelTypeDetector(),
    ): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val tracker = dataTimeoutTracker ?: DataTimeoutTracker(backgroundScope, dispatcher)
        return WheelConnectionManager(
            fakeBle, factory, backgroundScope, dispatcher,
            wheelTypeDetector = wheelTypeDetector,
            dataTimeoutTracker = tracker,
        )
    }

    /** Counts onDataReceived() calls that reach the data-timeout watchdog. */
    private class CountingDataTimeoutTracker(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) : DataTimeoutTracker(scope, dispatcher) {
        var onDataReceivedCount: Int = 0
            private set
        override fun onDataReceived() {
            onDataReceivedCount++
            super.onDataReceived()
        }
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
    fun `connect failure with hint leaves no decoder and no running timers`() = runTest(timeout = 0.1.seconds) {
        // Under B, the wheel-type hint passed to connect() is just identity —
        // it doesn't create a decoder or start the keep-alive timer until
        // services are discovered. So a connect failure that occurs before
        // service discovery cannot leak either resource.
        fakeBle.connectResult = false
        val keepAliveDecoder = FakeDecoder(keepAliveIntervalMs = 100L)
        val manager = createManager(
            decoder = keepAliveDecoder,
            factory = FakeDecoderFactory(keepAliveDecoder)
        )

        manager.connect("AA:BB:CC:DD:EE:FF", WheelType.KINGSONG)
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertFalse(manager.isKeepAliveRunning.value, "Keep-alive must not be running")
        assertNull(manager.getCurrentDecoder(), "No decoder should have been created from a hint alone")
    }

    @Test
    fun `hinted connect does not create decoder before services discovered`() = runTest(timeout = 0.1.seconds) {
        // Core invariant of design B: the wheel-type hint biases later detection
        // but never schedules init writes / keep-alive before BLE is configured.
        // Pre–Pass 1 the hint pre-created a decoder and dispatched init at connect
        // time, which silently lost the writes (BLE not connected yet) and could
        // leave the wheel stuck on Discovering Services for protocols whose
        // isReady() depends on init-command responses.
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF", WheelType.KINGSONG)
        runCurrent()

        assertNull(
            manager.getCurrentDecoder(),
            "Decoder must NOT exist until services are discovered (hint is identity-only)"
        )
        assertEquals(
            0,
            fakeFactory.createCount,
            "Factory must not be invoked at connect time"
        )
        assertFalse(
            manager.isKeepAliveRunning.value,
            "Keep-alive must not start until decoder is created post-discovery"
        )
        assertEquals(
            0,
            fakeBle.writtenData.size,
            "No writes (especially init commands) must occur before BLE is configured"
        )
    }

    @Test
    fun `connect exception transitions to Failed`() = runTest(timeout = 0.1.seconds) {
        // Use a BleManagerPort that throws
        val throwingBle = object : BleManagerPort {
            override val connectionState = fakeBle.connectionState
            override suspend fun connect(address: String, attemptId: Long): Boolean =
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
        assertNull(manager.telemetryState.value, "Telemetry should be null after disconnect")
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

    // ==================== Scan-evidence (Pass 1.5 substep 2) ====================

    @Test
    fun `connect snapshots advertisement from port into state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        val ad = BleAdvertisement(
            address = "AA:BB:CC:DD:EE:FF",
            advertisedName = "S22 PRO",
            peripheralName = "S22 PRO",
            rssi = -55,
            advertisedServiceUuids = setOf(BleUuids.Kingsong.SERVICE),
            manufacturerData = mapOf(0x004C to byteArrayOf(0x01, 0x02)),
            serviceData = emptyMap(),
            connectable = true,
            lastSeenMs = 1_234L,
        )
        fakeBle.advertisements["AA:BB:CC:DD:EE:FF"] = ad

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(ad, manager.lastAdvertisement)
    }

    @Test
    fun `connect with no prior scan leaves lastAdvertisement null`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        // Cache empty — no scan happened.

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertNull(manager.lastAdvertisement)
    }

    // Removed in Pass 3a: `connect with null hint leaves no hint to consume`
    // and `SAVED_PROFILE hint drives ambiguous decoder selection`. Both
    // tested the silent Ambiguous → GOTWAY_VIRTUAL / hinted-decoder
    // fallback that Pass 3a deletes. The hint plumbing itself is still
    // exercised by `hinted connect does not create decoder before
    // services discovered` and the iOS scan-name path; the
    // Ambiguous-as-Failed contract is pinned in
    // `Ambiguous detection result transitions to Failed (not GOTWAY_VIRTUAL)`.

    // ==================== attemptId staleness (Pass 1.5 substep 4) ====================

    @Test
    fun `rapid reconnect drops stale ServicesDiscovered`() = runTest(timeout = 0.1.seconds) {
        // Sequence: connect-1 → disconnect → connect-2 → straggler
        // ServicesDiscovered from session 1. The reducer must drop the stale
        // event so session 2's state is not corrupted by detection running
        // against the wrong device's services.
        val manager = createManager()

        manager.connect("AA:AA:AA:AA:AA:AA")
        runCurrent()
        manager.disconnect()
        runCurrent()
        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()

        // Now fire a stale ServicesDiscovered stamped with attempt 1 (the
        // first session). The current attempt is 2.
        val staleServices = DiscoveredServices(
            listOf(
                DiscoveredService(
                    uuid = BleUuids.Kingsong.SERVICE,
                    characteristics = listOf(BleUuids.Kingsong.READ_CHARACTERISTIC)
                )
            )
        )
        manager.onServicesDiscovered(staleServices, "KS-S18", attemptId = 1L)
        runCurrent()

        // Decoder must not have been created from the stale event.
        assertEquals(0, fakeFactory.createCount, "Stale ServicesDiscovered must not create a decoder")
        assertNull(manager.getCurrentDecoder(), "Stale event must not produce a decoder")
    }

    @Test
    fun `stale BleDisconnected does not transition to ConnectionLost`() = runTest(timeout = 0.1.seconds) {
        // Connect → reach Connected → fire a BleDisconnected stamped with a
        // PRIOR attemptId. The reducer must drop it; the current connection
        // must remain Connected.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 1500),
            identity = WheelIdentity(name = "KS")
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        // Stale BleDisconnected stamped with id=0 (a never-active session).
        manager.onBleDisconnected(
            "AA:BB:CC:DD:EE:FF",
            "Stale callback",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Stale callback"
            ),
            attemptId = 0L
        )
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "Stale BleDisconnected must not flip the connection to ConnectionLost"
        )
    }

    @Test
    fun `stale DataReceived does not advance decoder state`() = runTest(timeout = 0.1.seconds) {
        // Connect-1 → reach Connected → disconnect → connect-2 → fire stale
        // DataReceived stamped with attempt 1. Without the guard, the old
        // frame would feed the new session's decoder (or no decoder at all,
        // depending on timing) and corrupt state. With the guard it's dropped.
        val manager = createManager()
        manager.connect("AA:AA:AA:AA:AA:AA")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(speed = 4200),
        ))
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        assertEquals(4200, manager.telemetryState.value!!.speed)

        manager.disconnect()
        runCurrent()
        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()
        assertNull(manager.telemetryState.value, "Disconnect should clear telemetry")

        // Now fire a stale DataReceived with attempt 1 (first session) while
        // current is 2.
        manager.onDataReceived(byteArrayOf(0x99.toByte()), attemptId = 1L)
        runCurrent()

        assertNull(
            manager.telemetryState.value,
            "Stale DataReceived must not advance decoder state in the new session"
        )
    }

    @Test
    fun `stale DataReceived does not refresh data-timeout watchdog`() = runTest(timeout = 0.1.seconds) {
        // P2 from Codex Substep 4 review: a stale frame from a prior session
        // must NOT keep the new session's data-timeout watchdog alive. Without
        // the fix, the platform-side onDataReceived() reset ran before the
        // reducer's staleness guard, so straggler notifications would mask a
        // dead link on the new session.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val countingTracker = CountingDataTimeoutTracker(backgroundScope, dispatcher)
        val manager = createManager(dataTimeoutTracker = countingTracker)

        // Establish session 1.
        manager.connect("AA:AA:AA:AA:AA:AA")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Fresh frame from session 1 — should hit the watchdog.
        fakeDecoder.decodeResult = DecodeResult.Buffering
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()
        val countAfterFresh = countingTracker.onDataReceivedCount
        assertTrue(countAfterFresh >= 1, "Fresh frame must reset the watchdog")

        // Disconnect → reconnect to a new address. attemptId becomes 2.
        manager.disconnect()
        runCurrent()
        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()

        // Straggler frame stamped with the OLD attemptId.
        manager.onDataReceived(byteArrayOf(0x99.toByte()), attemptId = 1L)
        runCurrent()

        assertEquals(
            countAfterFresh,
            countingTracker.onDataReceivedCount,
            "Stale DataReceived must NOT refresh the data-timeout watchdog"
        )
    }

    @Test
    fun `disconnect clears lastAdvertisement`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        fakeBle.advertisements["AA:BB:CC:DD:EE:FF"] = BleAdvertisement(
            address = "AA:BB:CC:DD:EE:FF",
            advertisedName = "GotWay_008977",
            peripheralName = null,
            rssi = -60,
            advertisedServiceUuids = emptySet(),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            connectable = true,
            lastSeenMs = 1_000L,
        )
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertNotNull(manager.lastAdvertisement)

        manager.disconnect()
        runCurrent()

        assertNull(manager.lastAdvertisement)
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
    fun `onServicesDiscovered with InMotion V2 services and matching name detects type`() = runTest(timeout = 0.1.seconds) {
        // Pass 3a: the partial inMotionV2Services tree (Nordic UART +
        // ffe0/ffe4 only) doesn't match the full InMotion V2 fingerprint
        // in WheelTopologies.ALL, so detection runs the name-fallback
        // path. "V11Y-001" matches the InMotion V2 name pattern and
        // resolves to Detected(INMOTION_V2).
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onServicesDiscovered(inMotionV2Services, "V11Y-001")
        runCurrent()

        assertNotNull(manager.getCurrentDecoder())
        assertEquals(WheelType.INMOTION_V2, fakeFactory.lastCreatedType)
    }

    // Removed in Pass 4: `onServicesDiscovered with unknown services
    // transitions to Failed`. Unknown without a saved-profile/explicit hint
    // now surfaces ConnectionState.WheelTypeRequired so the picker UI can
    // run against the live peripheral. The new contract is pinned by the
    // Pass 4 tests at the bottom of this file (`Unknown topology with no
    // hint transitions to WheelTypeRequired (not Failed)` and friends).
    //
    // Removed in Pass 3a: `onServicesDiscovered with ambiguous services
    // uses GOTWAY_VIRTUAL`. The detector no longer returns Ambiguous for
    // a bare ffe0/ffe1 service tree — Ambiguous now requires conflicting
    // topology fingerprints. The Ambiguous → Failed contract is pinned by
    // the test in the Pass 3a section below.

    @Test
    fun `configureForWheel false transitions to Failed`() = runTest(timeout = 0.1.seconds) {
        // Fix D: when the BLE layer can't bind the read characteristic,
        // configureForWheel returns false. WCM must surface this as Failed
        // instead of leaving the user staring at "Discovering Services" forever.
        fakeBle.configureForWheelResult = false
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Failed).address)
        assertTrue(
            state.error.contains("characteristic", ignoreCase = true) ||
                state.error.contains("BLE", ignoreCase = true),
            "Failed error should mention the missing characteristic, got: ${state.error}"
        )
    }

    @Test
    fun `configureForWheel true keeps connection alive`() = runTest(timeout = 0.1.seconds) {
        // Default fakeBle.configureForWheelResult is true; this test pins
        // the happy path so we'd notice if the default flipped.
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.DiscoveringServices,
            "Expected DiscoveringServices on configureForWheel success, got ${manager.connectionState.value}"
        )
    }

    @Test
    fun `init commands run after ConfigureBle (Fix C ordering)`() = runTest(timeout = 0.1.seconds) {
        // Pass 1 Fix C invariant: ConfigureBle must precede
        // DispatchCommands(init). Pre-fix the order was reversed and
        // CommandScheduler's consumer would race ahead of the
        // synchronous BLE-bind, dropping init writes against a null
        // write characteristic — leaving wheels stuck on Discovering
        // Services with no error.
        //
        // Pass 1 originally pinned this through the Ambiguous-with-hint
        // path; Pass 3a deletes that path so the test is reframed to use
        // the Detected path (kingsongServices + name fallback). The
        // strict-mode FakeBleManager counts writes that happen before
        // configureForWheel returns true — any non-zero count means the
        // fix has regressed.
        val initData = byteArrayOf(0xAA.toByte(), 0x55, 0x9B.toByte())
        fakeDecoder.initCommandList = listOf(WheelCommand.SendBytes(initData))
        fakeBle.requireConfigureBeforeWrite = true

        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertNull(manager.getCurrentDecoder(), "Connect alone must not create a decoder")
        assertEquals(0, fakeFactory.createCount)

        // kingsongServices is the bare ffe0/ffe1 tree — won't match any
        // topology fingerprint, so detection runs the name fallback and
        // resolves "KS-S18" → KINGSONG.
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertEquals(WheelType.KINGSONG, fakeFactory.lastCreatedType)
        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType)
        assertTrue(
            fakeBle.writtenData.any { it.contentEquals(initData) },
            "Init commands must be written after BLE is configured; " +
                "written=${fakeBle.writtenData.size}, dropped=${fakeBle.writesDroppedBeforeConfigure}"
        )
        assertEquals(
            0,
            fakeBle.writesDroppedBeforeConfigure,
            "No writes should be dropped pre-configure; non-zero means ConfigureBle is racing init dispatch again"
        )
    }

    // Removed in Pass 3a:
    //   - `ambiguous services with no hint and no existing decoder fall back to GOTWAY_VIRTUAL`
    //   - `ConnectionLost reconnect preserves existing decoder type through Ambiguous discovery`
    // Both tested the now-deleted Ambiguous → GOTWAY_VIRTUAL fallback (and,
    // for the latter, the Ambiguous reconnect tiebreaker). Pass 3a's strict
    // contract surfaces all Ambiguous results as Failed; ConnectionLost
    // reconnect still preserves the decoder when topology resolves to a
    // matching wheel type, exercised by `Detected reconnect reuses existing
    // decoder` style tests further down.

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

        assertEquals(2500, manager.telemetryState.value!!.speed)
        assertEquals(8400, manager.telemetryState.value!!.voltage)
        assertEquals(85, manager.telemetryState.value!!.batteryLevel)
    }

    @Test
    fun `onDataReceived with no decoder logs and does nothing`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        // No decoder set up

        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertNull(manager.telemetryState.value, "Telemetry should be null when no decoder is set")
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
        assertEquals(2500, manager.telemetryState.value!!.speed)

        // Now return Buffering (incomplete frame)
        fakeDecoder.decodeResult = DecodeResult.Buffering
        manager.onDataReceived(byteArrayOf(0x02))
        runCurrent()

        // State should be unchanged
        assertEquals(2500, manager.telemetryState.value!!.speed)
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
    fun `BLE errors do not trigger ConnectionLost`() = runTest(timeout = 0.1.seconds) {
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

        // Even many consecutive BLE errors should NOT disconnect —
        // only the OS can declare the link dead (via onBleDisconnected)
        repeat(100) {
            manager.onBleError()
        }
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.Connected,
            "BLE errors should not trigger ConnectionLost")
        assertEquals(100, manager.consecutiveBleErrors.value)
    }

    @Test
    fun `onBleDisconnected triggers ConnectionLost`() = runTest(timeout = 0.1.seconds) {
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

        // OS-level disconnect — this is the only path to ConnectionLost
        manager.onBleDisconnected(
            "AA:BB:CC:DD:EE:FF",
            "Link supervision timeout",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Link supervision timeout"
            )
        )
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.ConnectionLost, "Expected ConnectionLost, got $state")
        assertEquals("Link supervision timeout", (state as ConnectionState.ConnectionLost).reason)
        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
        assertEquals(ConnectionIssueCode.PERIPHERAL_DISCONNECTED, state.issue.code)
    }

    // ==================== Active fallback resume preserves decoder ====================

    @Test
    fun `active fallback connect to same address as ConnectionLost preserves decoder`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        val initialDecoder = manager.getCurrentDecoder()
        assertNotNull(initialDecoder, "Decoder must exist after services discovered")
        val initialCreateCount = fakeFactory.createCount

        manager.onBleDisconnected(
            "AA:BB:CC:DD:EE:FF",
            "Connection timed out",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.CONNECTION_TIMED_OUT,
                message = "Connection timed out"
            )
        )
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.ConnectionLost)
        assertNotNull(manager.getCurrentDecoder(), "Decoder must survive ConnectionLost")
        assertFalse(fakeDecoder.resetCalled, "ConnectionLost must not reset the decoder")

        // Active fallback: WCM.connect() again for the same address while in
        // ConnectionLost. Pre-fix this would wipe WcmState and emit
        // ResetDecoder; post-fix it must preserve the existing decoder.
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        assertEquals(
            initialCreateCount, fakeFactory.createCount,
            "Same-address resume must not create a new decoder"
        )
        assertFalse(
            fakeDecoder.resetCalled,
            "Same-address resume must not call decoder.reset()"
        )
    }

    @Test
    fun `active fallback connect to different address as ConnectionLost resets decoder`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:AA:AA:AA:AA:AA")
        manager.onServicesDiscovered(kingsongServices, "KS-S18")
        runCurrent()

        manager.onBleDisconnected(
            "AA:AA:AA:AA:AA:AA",
            "Connection timed out",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.CONNECTION_TIMED_OUT,
                message = "Connection timed out"
            )
        )
        runCurrent()

        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()

        assertTrue(
            fakeDecoder.resetCalled,
            "Switching to a different address must still reset the decoder"
        )
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

    // ==================== Pass 3a: Ambiguous → Failed (no silent default) ====================

    @Test
    fun `Ambiguous detection result transitions to Failed (not GOTWAY_VIRTUAL)`() = runTest(timeout = 0.1.seconds) {
        // Pass 3a deletes the silent Ambiguous → GOTWAY_VIRTUAL fallback.
        // When the topology matcher returns multiple distinct candidates
        // and the device name can't disambiguate, the connection must
        // surface as Failed with the candidate list — not silently pick
        // the wrong protocol and leave the user staring at a broken
        // telemetry view.
        //
        // Trigger: a synthetic matcher with two fingerprints that share a
        // service tree and resolve to different wheel types. No real
        // ALL/PROXY pair currently exhibits this collision.
        val sharedServices = setOf(
            ServiceTopology(BleUuids.Gotway.SERVICE, setOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        )
        val ambiguousMatcher = WheelTopologyMatcher(listOf(
            WheelTopology("ninebot", WheelType.NINEBOT, sharedServices),
            WheelTopology("kingsong", WheelType.KINGSONG, sharedServices),
        ))
        val ambiguousDetector = WheelTypeDetector(ambiguousMatcher)

        val manager = createManager(wheelTypeDetector = ambiguousDetector)
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))
        manager.onServicesDiscovered(services, deviceName = null)
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Failed, "Expected Failed, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Failed).address)
        assertTrue(
            state.error.contains("ambiguous", ignoreCase = true) ||
                state.error.contains("multiple", ignoreCase = true),
            "Failed reason should mention ambiguity, got: ${state.error}"
        )
        assertNull(manager.getCurrentDecoder(), "No decoder should be created on Ambiguous")
    }

    // ==================== Pass 4: Unknown → WheelTypeRequired (picker entry point) ====================

    @Test
    fun `Unknown topology with no hint transitions to WheelTypeRequired (not Failed)`() = runTest(timeout = 0.1.seconds) {
        // Pass 4: when topology doesn't match any fingerprint AND name detection
        // also misses AND no saved-profile/explicit hint exists, the WCM stops
        // failing the connection and instead surfaces a WheelTypeRequired state.
        // The picker UI observes that state and lets the user pick a wheel type.
        // The BLE peripheral remains connected — picker confirmation runs
        // ConfigureBle against the existing session.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        val unknownServices = DiscoveredServices(listOf(
            DiscoveredService("12345678-0000-1000-8000-00805f9b34fb", emptyList())
        ))
        manager.onServicesDiscovered(unknownServices, deviceName = "MysteryWheel")
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(
            state is ConnectionState.WheelTypeRequired,
            "Expected WheelTypeRequired, got $state"
        )
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.WheelTypeRequired).address)
        assertEquals(unknownServices, state.services)
        assertEquals("MysteryWheel", state.deviceName)
        assertNull(manager.getCurrentDecoder(), "No decoder should be created until user picks")
    }

    @Test
    fun `Unknown topology with SAVED_PROFILE hint short-circuits picker`() = runTest(timeout = 0.1.seconds) {
        // Pass 4 plan guardrail: a saved per-MAC profile is user-confirmed
        // durable state. If the user already picked KINGSONG for this MAC
        // last connect, the next connect should NOT fire the picker — it
        // should trust the saved profile and proceed with that type.
        val manager = createManager()
        manager.connect(
            "AA:BB:CC:DD:EE:FF",
            ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SAVED_PROFILE),
        )
        runCurrent()

        val unknownServices = DiscoveredServices(listOf(
            DiscoveredService("12345678-0000-1000-8000-00805f9b34fb", emptyList())
        ))
        manager.onServicesDiscovered(unknownServices, deviceName = null)
        runCurrent()

        assertFalse(
            manager.connectionState.value is ConnectionState.WheelTypeRequired,
            "SAVED_PROFILE hint must short-circuit the picker, got ${manager.connectionState.value}",
        )
        assertEquals(WheelType.KINGSONG, fakeFactory.lastCreatedType)
        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType)
    }

    @Test
    fun `Unknown topology with EXPLICIT_API hint short-circuits picker`() = runTest(timeout = 0.1.seconds) {
        // EXPLICIT_API is the legacy `connect(address, wheelType)` path or a
        // confirmed picker dispatch carried as a fresh connect. Treat as
        // user-confirmed for the same reason as SAVED_PROFILE.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF", WheelType.VETERAN)
        runCurrent()

        val unknownServices = DiscoveredServices(listOf(
            DiscoveredService("12345678-0000-1000-8000-00805f9b34fb", emptyList())
        ))
        manager.onServicesDiscovered(unknownServices, deviceName = null)
        runCurrent()

        assertFalse(
            manager.connectionState.value is ConnectionState.WheelTypeRequired,
            "EXPLICIT_API hint must short-circuit the picker, got ${manager.connectionState.value}",
        )
        assertEquals(WheelType.VETERAN, fakeFactory.lastCreatedType)
    }

    @Test
    fun `Unknown topology with SCAN_NAME hint still triggers picker`() = runTest(timeout = 0.1.seconds) {
        // Pass 4 plan guardrail: the picker is the explicit user-driven
        // equivalent of the silent default deleted in Pass 3a. SCAN_NAME hints
        // are NOT user-confirmed — they're a heuristic guess from the BLE
        // advertised name observed at scan time. If both the topology AND
        // the post-discovery deviceName fail to resolve, we don't re-introduce
        // the silent fallback under a different name; the user must confirm
        // via the picker. (The SCAN_NAME-derived type still becomes a
        // "Likely" badge in the UI, but never auto-picks.)
        //
        // Realistic scenario: scan-time advertised name was `S22-3A0F` but
        // post-pairing peripheral.name is null/empty (some firmwares cache
        // the local name only on first connect). Detection runs against
        // null deviceName and topology miss → Unknown.
        val manager = createManager()
        manager.connect(
            "AA:BB:CC:DD:EE:FF",
            ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SCAN_NAME, rawName = "S22-3A0F"),
        )
        runCurrent()

        val unknownServices = DiscoveredServices(listOf(
            DiscoveredService("12345678-0000-1000-8000-00805f9b34fb", emptyList())
        ))
        manager.onServicesDiscovered(unknownServices, deviceName = null)
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.WheelTypeRequired,
            "SCAN_NAME hint must NOT short-circuit the picker, got ${manager.connectionState.value}",
        )
        assertNull(manager.getCurrentDecoder(), "No decoder should be created until user picks")
    }

    @Test
    fun `confirmWheelType from WheelTypeRequired transitions to Connected with picked decoder`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        val unknownServices = DiscoveredServices(listOf(
            DiscoveredService("12345678-0000-1000-8000-00805f9b34fb", emptyList())
        ))
        manager.onServicesDiscovered(unknownServices, deviceName = "MysteryWheel")
        runCurrent()
        require(manager.connectionState.value is ConnectionState.WheelTypeRequired)

        manager.confirmWheelType(WheelType.KINGSONG)
        runCurrent()

        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Connected, "Expected Connected, got $state")
        assertEquals("AA:BB:CC:DD:EE:FF", (state as ConnectionState.Connected).address)
        assertEquals(WheelType.KINGSONG, fakeFactory.lastCreatedType)
        assertEquals(WheelType.KINGSONG, manager.identityState.value.wheelType)
        assertNotNull(fakeBle.lastConfigureForWheel, "ConfigureBle must run against the existing peripheral")
    }

    @Test
    fun `confirmWheelType outside WheelTypeRequired is rejected`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        // Disconnected → confirm should be a no-op
        manager.confirmWheelType(WheelType.KINGSONG)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertNull(manager.getCurrentDecoder())
        assertEquals(0, fakeFactory.createCount)
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

    override fun buildCommand(command: WheelCommand, state: DecoderState?): List<WheelCommand> = buildCommandResult

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
