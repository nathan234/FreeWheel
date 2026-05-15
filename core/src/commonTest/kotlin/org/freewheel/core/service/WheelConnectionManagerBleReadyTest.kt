package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecodedData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Commit 1 of `KINGSONG_BLE_PARITY_PLAN.md`: tests for the BLE-ready /
 * write-ack plumbing that the rest of the plan builds on. These tests focus
 * on signal routing only — they do not exercise any Kingsong-specific
 * transport behavior (heartbeat, 0x5E, KSE), which lands in later commits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerBleReadyTest {

    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeDecoder: FakeDecoder
    private lateinit var fakeFactory: FakeDecoderFactory

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
        fakeDecoder = FakeDecoder()
        fakeFactory = FakeDecoderFactory(fakeDecoder)
    }

    private fun TestScope.createManager(): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(
            fakeBle, fakeFactory, backgroundScope, dispatcher,
            wheelTypeDetector = WheelTypeDetector(),
            dataTimeoutTracker = DataTimeoutTracker(backgroundScope, dispatcher),
        )
    }

    /** Drive the manager from a fresh state through to [ConnectionState.Connected]. */
    private fun bringToConnected(manager: WheelConnectionManager, address: String) {
        manager.connect(address)
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        fakeDecoder.decodeResult = DecodeResult.Success(
            DecodedData(
                telemetry = TelemetryState(speed = 1000),
                identity = WheelIdentity(name = "KS-S18"),
            )
        )
        fakeDecoder.ready = true
        manager.onDataReceived(byteArrayOf(0x01))
    }

    // ==================== isBleReady semantics ====================

    @Test
    fun `isBleReady starts false`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        runCurrent()
        assertFalse(manager.isBleReady.value)
    }

    @Test
    fun `connect alone does not set isBleReady`() = runTest(timeout = 0.1.seconds) {
        // The plan is explicit that BLE-ready must NOT be inferred from
        // service discovery or connect-success. Only the explicit notify
        // callback should flip the flag.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertFalse(
            manager.isBleReady.value,
            "isBleReady must not be inferred from connect/services-discovery",
        )
    }

    @Test
    fun `Connected state alone does not set isBleReady`() = runTest(timeout = 0.1.seconds) {
        // Even reaching Connected (decoder ready, telemetry flowing) must
        // not implicitly flip isBleReady — the platform notify callback is
        // the single source of truth.
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        assertFalse(manager.isBleReady.value)
    }

    @Test
    fun `onBleReady flips isBleReady to true`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // The default attemptId resolves to the current session's id, so we
        // do not need to thread it through every assertion.
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertTrue(manager.isBleReady.value)
    }

    @Test
    fun `stale onBleReady is dropped`() = runTest(timeout = 0.1.seconds) {
        // Sequence: connect-1 → disconnect → connect-2 → straggler BleReady
        // from session 1. The reducer must drop it.
        val manager = createManager()
        manager.connect("AA:AA:AA:AA:AA:AA")
        runCurrent()
        manager.disconnect()
        runCurrent()
        manager.connect("BB:BB:BB:BB:BB:BB")
        runCurrent()

        // Fire BleReady stamped with the first session's id.
        manager.onBleReady("AA:AA:AA:AA:AA:AA", attemptId = 1L)
        runCurrent()

        assertFalse(
            manager.isBleReady.value,
            "Stale BleReady must not flip the current session's flag",
        )
    }

    @Test
    fun `user disconnect resets isBleReady`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isBleReady.value)

        manager.disconnect()
        runCurrent()

        assertFalse(manager.isBleReady.value)
    }

    @Test
    fun `BleDisconnected resets isBleReady`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isBleReady.value)

        // OS-level unexpected disconnect. The session enters ConnectionLost;
        // notifications go down with the BLE link, so isBleReady must reset.
        manager.onBleDisconnected(
            address = "AA:BB:CC:DD:EE:FF",
            reason = "Link lost",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Link lost",
            ),
        )
        runCurrent()

        assertTrue(manager.connectionState.value is ConnectionState.ConnectionLost)
        assertFalse(manager.isBleReady.value)
    }

    @Test
    fun `same-wheel resume clears isBleReady until notify confirmed again`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isBleReady.value)

        // Go to ConnectionLost.
        manager.onBleDisconnected(
            address = "AA:BB:CC:DD:EE:FF",
            reason = "Link lost",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Link lost",
            ),
        )
        runCurrent()
        assertFalse(manager.isBleReady.value)

        // Active-fallback reconnect from ConnectionLost (decoder still attached).
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertFalse(
            manager.isBleReady.value,
            "Resume connect must reset isBleReady — the BLE link tore down across the gap",
        )
    }

    @Test
    fun `repeated onBleReady is idempotent`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertTrue(manager.isBleReady.value)
    }

    // ==================== Write-ack routing ====================

    @Test
    fun `onBleWriteAck dispatches to writeAckCallback`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        val acks = mutableListOf<BleWriteAck>()
        manager.writeAckCallback = { acks.add(it) }

        val ack = BleWriteAck(
            attemptId = 7L,
            success = true,
            data = byteArrayOf(0x5E),
        )
        manager.onBleWriteAck(ack)
        runCurrent()

        assertEquals(1, acks.size)
        val received = acks.single()
        assertEquals(7L, received.attemptId)
        assertTrue(received.success)
        assertNotNull(received.data)
        assertEquals(1, received.data.size)
        assertEquals(0x5E.toByte(), received.data[0])
    }

    @Test
    fun `onBleWriteAck failure carries error string`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        val acks = mutableListOf<BleWriteAck>()
        manager.writeAckCallback = { acks.add(it) }

        manager.onBleWriteAck(
            BleWriteAck(
                attemptId = 2L,
                success = false,
                data = byteArrayOf(),
                error = "GATT_ERROR",
            )
        )
        runCurrent()

        val received = acks.single()
        assertFalse(received.success)
        assertEquals("GATT_ERROR", received.error)
    }

    @Test
    fun `onBleWriteAck without callback does not throw`() = runTest(timeout = 0.1.seconds) {
        // Commit 1's write-ack path is observation-only. With no callback
        // registered (the production default for non-WITH_RESPONSE writes
        // today) the manager must accept acks silently.
        val manager = createManager()
        manager.onBleWriteAck(
            BleWriteAck(attemptId = 1L, success = true, data = byteArrayOf(0x01))
        )
        runCurrent()
        // No assertion needed — reaching this line proves the event loop
        // accepted the ack without throwing.
    }

    @Test
    fun `onBleWriteAck does not mutate connection state`() = runTest(timeout = 0.1.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        val before = manager.connectionState.value
        val readyBefore = manager.isBleReady.value

        manager.onBleWriteAck(
            BleWriteAck(attemptId = 99L, success = true, data = byteArrayOf(0x01))
        )
        runCurrent()

        assertEquals(before, manager.connectionState.value)
        assertEquals(readyBefore, manager.isBleReady.value)
    }
}
