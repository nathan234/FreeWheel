package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.protocol.WheelCommand
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Commit 2 of `KINGSONG_BLE_PARITY_PLAN.md`: proves that the
 * [WheelTransportProfile] plumbing through [WheelConnectionManager] and the
 * platform port is byte-equivalent to pre-Commit-2 behavior when every wheel
 * still uses [WheelTransportProfile.Default].
 *
 * Out of scope: any Kingsong-specific transport behavior (heartbeat, 0x5E,
 * KSE) — those land in later commits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerTransportProfileTest {

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

    // ==================== Default profile equivalence ====================

    @Test
    fun `forKingsong() carries the default transport profile`() {
        val info = WheelConnectionInfo.forKingsong()
        assertEquals(WheelTransportProfile.Default, info.transportProfile)
    }

    @Test
    fun `every existing factory returns the default profile`() {
        // Tightens the equivalence claim — no factory secretly opts a wheel
        // into a non-default profile in Commit 2. Later commits introduce
        // KingsongClassic / KingsongKse.
        val infos = listOf(
            WheelConnectionInfo.forKingsong(),
            WheelConnectionInfo.forGotway(),
            WheelConnectionInfo.forVeteran(),
            WheelConnectionInfo.forInMotion(),
            WheelConnectionInfo.forInMotionV2(),
            WheelConnectionInfo.forNinebot(),
            WheelConnectionInfo.forNinebotZ(),
            WheelConnectionInfo.forLeaperkim(),
        )
        for (info in infos) {
            assertEquals(
                WheelTransportProfile.Default,
                info.transportProfile,
                "Wheel ${info.wheelType} should use the default transport profile in Commit 2",
            )
        }
    }

    @Test
    fun `default profile dispatches WITHOUT_RESPONSE exactly once per command byte`() = runTest(timeout = 0.5.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // Send a single SendBytes command and confirm the dispatch hits the
        // BLE port once with WITHOUT_RESPONSE (today's behavior).
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        assertEquals(1, fakeBle.writeRequests.size, "Default profile must not duplicate or retry writes")
        val request = fakeBle.writeRequests.single()
        assertEquals(BleWriteType.WITHOUT_RESPONSE, request.writeType)
        assertTrue(request.data.contentEquals(byteArrayOf(0x42)))
    }

    @Test
    fun `dispatch preserves byte order under the default profile`() = runTest(timeout = 0.5.seconds) {
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        // The decoder is wired to translate a command into three raw byte
        // packets; the WCM threads them through CommandScheduler ->
        // WriteCoordinator -> bleManager.write in FIFO order.
        fakeDecoder.buildCommandResult = listOf(
            WheelCommand.SendBytes(byteArrayOf(0x01)),
            WheelCommand.SendBytes(byteArrayOf(0x02)),
            WheelCommand.SendBytes(byteArrayOf(0x03)),
        )
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        assertEquals(
            listOf(byteArrayOf(0x01), byteArrayOf(0x02), byteArrayOf(0x03)).map { it.toList() },
            fakeBle.writeRequests.map { it.data.toList() },
        )
        assertTrue(fakeBle.writeRequests.all { it.writeType == BleWriteType.WITHOUT_RESPONSE })
    }

    @Test
    fun `configureForWheel receives the wheel connection info`() = runTest(timeout = 0.5.seconds) {
        // Tightens the new plumbing path — Commit 2 expects the platform
        // layer to receive the full info (UUIDs + transport profile) in one
        // call rather than positional UUID args.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val info = fakeBle.lastConfigureConnectionInfo
        assertNotNull(info, "configureForWheel must run after wheel-type detection")
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(WheelTransportProfile.Default, info.transportProfile)
    }

    @Test
    fun `disconnect resets the write coordinator cadence`() = runTest(timeout = 0.5.seconds) {
        // Review fix: the coordinator carries lastWriteAt for the lifetime
        // of the WCM. Without a reset hook, a non-default spacing profile
        // (Commit 3+) would let the prior session's last-write timestamp
        // delay the first write of the new session. WCM must call
        // writeCoordinator.reset() at every session teardown via the
        // StopTimers effect.
        val tracker = ResetTrackingWriteCoordinator()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = WheelConnectionManager(
            fakeBle, fakeFactory, backgroundScope, dispatcher,
            wheelTypeDetector = WheelTypeDetector(),
            dataTimeoutTracker = DataTimeoutTracker(backgroundScope, dispatcher),
            writeCoordinator = tracker,
        )
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val before = tracker.resetCount
        manager.disconnect()
        runCurrent()

        assertTrue(
            tracker.resetCount > before,
            "Disconnect must reset write-coordinator timing state (was $before, now ${tracker.resetCount})",
        )
    }
}

/** Observable [WriteCoordinator] subclass that counts [reset] invocations. */
private class ResetTrackingWriteCoordinator : WriteCoordinator() {
    var resetCount: Int = 0
        private set

    override fun reset() {
        resetCount++
        super.reset()
    }
}
