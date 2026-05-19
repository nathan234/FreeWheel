package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.WheelCommand
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Commit 3 of `KINGSONG_BLE_PARITY_PLAN.md`. Tests that classic Kingsong
 * carries the [WheelTransportProfile.KingsongClassic] profile end-to-end:
 *
 * - factory wiring and exact frame bytes
 * - BLE-ready gating of warmup + heartbeat
 * - capture / [BleWriteRequest.annotation] routing for transport-generated traffic
 * - decoder-driven keepalive suppression under [TransportKeepAlivePolicy.FixedFrame]
 * - BLE-ready-scoped stop/restart across disconnect/resume cycles
 *
 * Out of scope: KSE (Commit 4), command-execution UX (Commit 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerKingsongTransportTest {

    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeDecoder: FakeDecoder
    private lateinit var fakeFactory: FakeDecoderFactory

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
        // Non-zero keepAliveIntervalMs surfaces the decoder-driven path so
        // we can prove [TransportKeepAlivePolicy.FixedFrame] suppresses it.
        fakeDecoder = FakeDecoder(
            wheelType = WheelType.KINGSONG,
            keepAliveIntervalMs = 250L,
            keepAliveCommand = WheelCommand.SendBytes(byteArrayOf(0xDE.toByte())),
        )
        fakeFactory = FakeDecoderFactory(fakeDecoder)
    }

    private fun TestScope.createManager(): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(
            fakeBle, fakeFactory, backgroundScope, dispatcher,
            wheelTypeDetector = WheelTypeDetector(),
            keepAliveTimer = KeepAliveTimer(backgroundScope, dispatcher),
            dataTimeoutTracker = DataTimeoutTracker(backgroundScope, dispatcher),
        )
    }

    // The exact 20-byte frames pinned by the plan + the official Kingsong
    // DLC app (BleService.java:347/400/404/797, C6720gh.java:512).

    private val kingsongHeartbeatFrame: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x55.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00,
        0x14, 0x5A, 0x5A,
    )

    private val kingsongWarmupFrame: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x55.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x5E,
        0x14, 0x5A, 0x5A,
    )

    /** Bring the manager to the point where BLE-ready is the only thing left to fire. */
    private fun bringToConnected(manager: WheelConnectionManager, address: String) {
        manager.connect(address)
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        // Note: we deliberately do NOT call onBleReady here — each test
        // chooses when to fire it.
    }

    // ==================== Profile shape ====================

    @Test
    fun `forKingsong carries the KingsongClassic transport profile`() {
        val info = WheelConnectionInfo.forKingsong()
        assertEquals(WheelTransportProfile.KingsongClassic, info.transportProfile)
    }

    @Test
    fun `KingsongClassic profile fields match the official app behavior`() {
        val profile = WheelTransportProfile.KingsongClassic
        assertEquals(BleWriteType.WITHOUT_RESPONSE, profile.writeType)
        assertTrue(profile.requestMaxMtu)
        assertEquals(50L, profile.interWriteSpacingMs)
        assertEquals(1, profile.retryPolicy.maxRetries)
        assertEquals(50L, profile.retryPolicy.retryBackoffMs)

        val keepAlive = profile.keepAlivePolicy as TransportKeepAlivePolicy.FixedFrame
        assertEquals(1000L, keepAlive.intervalMs)
        assertEquals("ks-heartbeat", keepAlive.annotation)
        assertTrue(
            keepAlive.frame.contentEquals(kingsongHeartbeatFrame),
            "Heartbeat frame must match the official app's 20-byte blank Kingsong packet"
        )

        val warmup = profile.postConnectWarmups.single()
        assertEquals(2500L, warmup.delayMs)
        assertEquals("ks-0x5e-warmup", warmup.annotation)
        assertTrue(
            warmup.frame.contentEquals(kingsongWarmupFrame),
            "Warmup frame must match the official app's 0x5E packet (byte 16 = 0x5E)"
        )
    }

    // ==================== Warmup / heartbeat gating ====================

    @Test
    fun `no warmup or heartbeat fires before BleReady`() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Even past the warmup deadline (2500ms) and several heartbeat
        // intervals, nothing transport-driven should fire because notify
        // success has not been signalled.
        advanceTimeBy(3000)
        runCurrent()

        val transportWrites = fakeBle.writeRequests.filter {
            it.annotation == "ks-heartbeat" || it.annotation == "ks-0x5e-warmup"
        }
        assertTrue(
            transportWrites.isEmpty(),
            "Transport-driven traffic must not run before BleReady; saw $transportWrites",
        )
    }

    @Test
    fun `BleReady fires the first heartbeat 1000ms after notify success`() = runTest(timeout = 2.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Just before the first interval — nothing yet.
        advanceTimeBy(999)
        runCurrent()
        assertEquals(
            0,
            fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" },
            "Heartbeat must not fire before 1000ms have elapsed since BleReady",
        )

        // Cross the 1000ms threshold — exactly one heartbeat.
        advanceTimeBy(2)
        runCurrent()
        val firstBatch = fakeBle.writeRequests.filter { it.annotation == "ks-heartbeat" }
        assertEquals(1, firstBatch.size, "Expected exactly one heartbeat at the 1000ms mark")
        assertTrue(
            firstBatch.single().data.contentEquals(kingsongHeartbeatFrame),
            "Heartbeat payload must match the pinned 20-byte Kingsong blank frame",
        )
        assertEquals(BleWriteType.WITHOUT_RESPONSE, firstBatch.single().writeType)
    }

    @Test
    fun `BleReady fires the 0x5E warmup once after 2500ms`() = runTest(timeout = 5.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        advanceTimeBy(2499)
        runCurrent()
        assertEquals(
            0,
            fakeBle.writeRequests.count { it.annotation == "ks-0x5e-warmup" },
            "Warmup must not fire before 2500ms have elapsed since BleReady",
        )

        // Advance past the 2500ms deadline plus the [WriteCoordinator]
        // spacing slop that may sit between a recent heartbeat (t=2000)
        // and the warmup. In production the real-time gap exceeds spacing,
        // but virtual time forces us to wait it out explicitly.
        advanceTimeBy(100)
        runCurrent()
        val warmups = fakeBle.writeRequests.filter { it.annotation == "ks-0x5e-warmup" }
        assertEquals(1, warmups.size, "Expected exactly one warmup at the 2500ms mark")
        assertTrue(
            warmups.single().data.contentEquals(kingsongWarmupFrame),
            "Warmup payload must match the pinned 20-byte 0x5E frame",
        )

        // Run well past the warmup deadline to prove it is one-shot.
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(
            1,
            fakeBle.writeRequests.count { it.annotation == "ks-0x5e-warmup" },
            "Warmup must not recur — it is a one-shot post-connect frame",
        )
    }

    // ==================== Annotation routing ====================

    @Test
    fun `transport traffic carries the pinned capture annotations`() = runTest(timeout = 2.seconds) {
        val captured = mutableListOf<Triple<ByteArray, BlePacketDirection, String>>()
        val manager = createManager()
        manager.captureCallback = { data, direction, annotation ->
            captured.add(Triple(data.copyOf(), direction, annotation))
        }

        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Cross the heartbeat threshold.
        advanceTimeBy(1001)
        runCurrent()

        val heartbeatCapture = captured.firstOrNull { it.third == "ks-heartbeat" }
        assertTrue(
            heartbeatCapture != null,
            "Capture callback must see the heartbeat tagged ks-heartbeat",
        )
        assertEquals(BlePacketDirection.TX, heartbeatCapture.second)
        assertTrue(heartbeatCapture.first.contentEquals(kingsongHeartbeatFrame))

        // And the warmup once it fires.
        advanceTimeBy(1500)
        runCurrent()
        val warmupCapture = captured.firstOrNull { it.third == "ks-0x5e-warmup" }
        assertTrue(
            warmupCapture != null,
            "Capture callback must see the 0x5E warmup tagged ks-0x5e-warmup",
        )
        assertEquals(BlePacketDirection.TX, warmupCapture.second)
        assertTrue(warmupCapture.first.contentEquals(kingsongWarmupFrame))
    }

    @Test
    fun `transport traffic propagates annotation onto BleWriteRequest`() = runTest(timeout = 2.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Past both deadlines plus the [WriteCoordinator] spacing the
        // warmup may inherit from the t=2000 heartbeat.
        advanceTimeBy(2600)
        runCurrent()

        val heartbeatRequest = fakeBle.writeRequests.firstOrNull { it.annotation == "ks-heartbeat" }
        val warmupRequest = fakeBle.writeRequests.firstOrNull { it.annotation == "ks-0x5e-warmup" }
        assertTrue(heartbeatRequest != null, "Heartbeat must reach BleWriteRequest with ks-heartbeat annotation")
        assertTrue(warmupRequest != null, "Warmup must reach BleWriteRequest with ks-0x5e-warmup annotation")
        assertEquals(BleWriteType.WITHOUT_RESPONSE, heartbeatRequest.writeType)
        assertEquals(BleWriteType.WITHOUT_RESPONSE, warmupRequest.writeType)
    }

    @Test
    fun `semantic command writes carry an empty annotation`() = runTest(timeout = 1.seconds) {
        // Sanity check on the negative case: only transport-generated traffic
        // should be tagged. A SendBytes command must reach the BLE port with
        // an empty annotation so capture tooling can filter cleanly.
        val captured = mutableListOf<Triple<ByteArray, BlePacketDirection, String>>()
        val manager = createManager()
        manager.captureCallback = { data, direction, annotation ->
            captured.add(Triple(data.copyOf(), direction, annotation))
        }
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val cmdCapture = captured.firstOrNull { it.first.size == 1 && it.first[0] == 0x42.toByte() }
        assertTrue(cmdCapture != null, "Capture callback must see the semantic command write")
        assertEquals("", cmdCapture.third)
    }

    // ==================== Keepalive policy interaction ====================

    @Test
    fun `FixedFrame profile suppresses decoder-driven keepalive`() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertFalse(
            manager.isKeepAliveRunning.value,
            "Decoder-driven keepalive must not start under TransportKeepAlivePolicy.FixedFrame " +
                "(${WheelTransportProfile.KingsongClassic.keepAlivePolicy})",
        )

        // Run long enough that the decoder-driven keepalive command would have
        // fired several times if the path were active. The wheel should never
        // see the decoder keepalive payload.
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(
            0,
            fakeBle.writeRequests.count { it.data.contentEquals(byteArrayOf(0xDE.toByte())) },
            "Decoder keepalive command must not reach the BLE port when FixedFrame replaces it",
        )
    }

    @Test
    fun `UseDecoder profile still starts decoder-driven keepalive`() = runTest(timeout = 1.seconds) {
        // Regression guard for non-Kingsong wheels — they must keep the
        // pre-Commit-3 decoder keepalive path.
        fakeDecoder = FakeDecoder(
            wheelType = WheelType.GOTWAY,
            keepAliveIntervalMs = 250L,
            keepAliveCommand = WheelCommand.SendBytes(byteArrayOf(0xAA.toByte())),
        )
        fakeFactory = FakeDecoderFactory(fakeDecoder)
        val manager = createManager()

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        runCurrent()

        assertTrue(
            manager.isKeepAliveRunning.value,
            "Default (UseDecoder) profile must still start decoder-driven keepalive",
        )
    }

    // ==================== Disconnect / resume lifecycle ====================

    @Test
    fun `BleDisconnected stops transport maintenance even without StopTimers`() = runTest(timeout = 3.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // First heartbeat fires.
        advanceTimeBy(1001)
        runCurrent()
        val heartbeatsBefore = fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" }
        assertEquals(1, heartbeatsBefore)

        // OS-level disconnect — link is down, transport maintenance must stop.
        manager.onBleDisconnected(
            address = "AA:BB:CC:DD:EE:FF",
            reason = "Link lost",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Link lost",
            ),
        )
        runCurrent()

        // Run well past several intervals. The disconnected fake refuses
        // writes (returns Not connected), but the heartbeat job should be
        // cancelled outright — no further BleWriteRequests for the
        // heartbeat annotation should be recorded.
        val countAtDisconnect = fakeBle.writeRequests.size
        advanceTimeBy(5000)
        runCurrent()

        val newRequests = fakeBle.writeRequests.drop(countAtDisconnect)
        assertTrue(
            newRequests.none { it.annotation == "ks-heartbeat" },
            "Heartbeat must stop on BleDisconnected; saw ${newRequests.filter { it.annotation == "ks-heartbeat" }}",
        )
        assertTrue(
            newRequests.none { it.annotation == "ks-0x5e-warmup" },
            "Pending warmup must be cancelled on BleDisconnected; saw ${newRequests.filter { it.annotation == "ks-0x5e-warmup" }}",
        )
    }

    @Test
    fun `next BleReady restarts transport maintenance after a disconnect`() = runTest(timeout = 5.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Burn through one heartbeat, then disconnect.
        advanceTimeBy(1001)
        runCurrent()
        manager.onBleDisconnected(
            address = "AA:BB:CC:DD:EE:FF",
            reason = "Link lost",
            issue = ConnectionIssue.recoverable(
                code = ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                message = "Link lost",
            ),
        )
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()

        val beforeReconnect = fakeBle.writeRequests.size
        val heartbeatsBeforeReconnect = fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" }
        val warmupsBeforeReconnect = fakeBle.writeRequests.count { it.annotation == "ks-0x5e-warmup" }

        // Active-fallback resume — manager re-enters Connecting; FakeBleManager
        // marks itself isConnected = true once connect() is called.
        manager.connect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        // The platform notify callback fires again after the reconnect.
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        // Heartbeat 1000ms after the second BleReady, plus the
        // [WriteCoordinator] spacing slop the second-session stamp may
        // inherit from the first session (the resume path does not reset
        // the coordinator — that ensures real-world reconnects do not lose
        // their cadence — but in virtual time we have to wait it out).
        advanceTimeBy(1100)
        runCurrent()
        assertEquals(
            heartbeatsBeforeReconnect + 1,
            fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" },
            "BleReady after reconnect must restart the heartbeat",
        )

        // Warmup ~2500ms after the second BleReady. We advanced 1100 already;
        // bump another 1500 to clear the warmup deadline plus spacing.
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(
            warmupsBeforeReconnect + 1,
            fakeBle.writeRequests.count { it.annotation == "ks-0x5e-warmup" },
            "BleReady after reconnect must replay the 0x5E warmup",
        )

        // Sanity — we have at least the new heartbeat + warmup since the reconnect.
        assertTrue(fakeBle.writeRequests.size >= beforeReconnect + 2)
    }

    @Test
    fun `user disconnect stops transport maintenance via StopTimers`() = runTest(timeout = 3.seconds) {
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        advanceTimeBy(1001)
        runCurrent()
        val heartbeatsBefore = fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" }
        assertTrue(heartbeatsBefore >= 1)

        manager.disconnect()
        runCurrent()

        val countAtDisconnect = fakeBle.writeRequests.size
        advanceTimeBy(5000)
        runCurrent()
        val newRequests = fakeBle.writeRequests.drop(countAtDisconnect)
        assertTrue(
            newRequests.none { it.annotation == "ks-heartbeat" },
            "User disconnect must stop the heartbeat alongside StopTimers",
        )
    }

    @Test
    fun `repeated BleReady does not stack transport maintenance`() = runTest(timeout = 3.seconds) {
        // Idempotency guard: a duplicate notify callback (e.g. multiple
        // characteristics flipping into the notifying state) must not double
        // the heartbeat traffic.
        val manager = createManager()
        bringToConnected(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.onBleReady("AA:BB:CC:DD:EE:FF")
        runCurrent()

        advanceTimeBy(1001)
        runCurrent()
        assertEquals(
            1,
            fakeBle.writeRequests.count { it.annotation == "ks-heartbeat" },
            "Duplicate BleReady must remain idempotent — at most one heartbeat per interval",
        )
    }
}

