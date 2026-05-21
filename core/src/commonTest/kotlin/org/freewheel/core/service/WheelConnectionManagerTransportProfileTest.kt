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
 * Commit 2 / Commit 3 of `KINGSONG_BLE_PARITY_PLAN.md`: proves the
 * [WheelTransportProfile] plumbing through [WheelConnectionManager] still
 * routes default-profile wheels byte-equivalently to pre-Commit-2 behavior,
 * and that Kingsong now carries [WheelTransportProfile.KingsongClassic] from
 * the factory through every dispatch path. Kingsong-specific heartbeat /
 * warmup execution is covered in `WheelConnectionManagerKingsongTransportTest`.
 *
 * Out of scope: KSE (Commit 4), command-execution UX (Commit 5).
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

    // ==================== Profile assignment ====================

    @Test
    fun `forKingsong() carries the KingsongClassic transport profile`() {
        // Commit 3: classic Kingsong is the first wheel to opt out of
        // Default. KSE will get its own factory + profile in Commit 4.
        val info = WheelConnectionInfo.forKingsong()
        assertEquals(WheelTransportProfile.KingsongClassic, info.transportProfile)
    }

    @Test
    fun `every non-Kingsong factory still returns the default profile`() {
        // Tightens the equivalence claim — Commit 3 only touches the
        // classic Kingsong factory. Every other wheel must stay on Default
        // so non-Kingsong behavior is byte-equivalent to pre-Commit-3.
        val infos = listOf(
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
                "Wheel ${info.wheelType} should still use the default transport profile in Commit 3",
            )
        }
    }

    @Test
    fun `default profile dispatches WITHOUT_RESPONSE exactly once per command byte`() = runTest(timeout = 0.5.seconds) {
        // Uses GOTWAY to keep the wheel on [WheelTransportProfile.Default]
        // (Commit 3 promoted Kingsong onto KingsongClassic, which adds
        // inter-write spacing and would not pass under runCurrent without
        // explicit virtual-time advance).
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
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
        // GOTWAY for the same reason as above — keeps the wheel on Default
        // so no spacing delay sits between consecutive writes.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
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
        // Commit 3 carries [WheelTransportProfile.KingsongClassic] all the
        // way into the platform-layer configureForWheel call so future
        // transport-aware platform behavior (Commit 4 MTU divergence) can
        // read the profile without a second plumbing pass.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()

        val info = fakeBle.lastConfigureConnectionInfo
        assertNotNull(info, "configureForWheel must run after wheel-type detection")
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(WheelTransportProfile.KingsongClassic, info.transportProfile)
    }

    @Test
    fun `reduceServicesDiscovered preserves a detected KSE transport profile`() = runTest(timeout = 0.5.seconds) {
        // Commit 4 load-bearing seam: when the detector returns Detected
        // with a KSE [WheelConnectionInfo] (AD00 + KingsongKse profile),
        // WCM must hand that exact info to the platform layer.
        // Pre-Commit-4, WCM re-derived the info from `result.wheelType`
        // via [WheelConnectionInfo.forType], which always returns classic
        // Kingsong (FFE0 + KingsongClassic) — silently collapsing KSE.
        val manager = createManager()
        manager.connect("AA:BB:CC:DD:EE:FF")
        val kseServices = org.freewheel.core.ble.DiscoveredServices(
            services = listOf(
                org.freewheel.core.ble.DiscoveredService(
                    uuid = org.freewheel.core.ble.BleUuids.KingsongKse.SERVICE,
                    characteristics = listOf(
                        org.freewheel.core.ble.BleUuids.KingsongKse.WRITE_CHARACTERISTIC,
                        org.freewheel.core.ble.BleUuids.KingsongKse.READ_CHARACTERISTIC,
                    )
                )
            )
        )
        manager.onServicesDiscovered(kseServices, "KS-E1-9876")
        runCurrent()

        val info = fakeBle.lastConfigureConnectionInfo
        assertNotNull(info, "configureForWheel must run after KSE service discovery")
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(
            WheelTransportProfile.KingsongKse,
            info.transportProfile,
            "WCM must pass the detector-supplied KSE transport profile through to ConfigureBle",
        )
        assertEquals(
            org.freewheel.core.ble.BleUuids.KingsongKse.SERVICE,
            info.readServiceUuid,
            "KSE wheels must keep AD00 — not collapse to classic FFE0",
        )
        assertEquals(
            org.freewheel.core.ble.BleUuids.KingsongKse.WRITE_CHARACTERISTIC,
            info.writeCharacteristicUuid,
            "KSE wheels must keep AD01 — not collapse to classic FFE1",
        )
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
