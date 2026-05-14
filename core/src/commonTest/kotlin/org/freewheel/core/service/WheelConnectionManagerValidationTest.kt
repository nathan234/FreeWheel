package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.logging.ConnectionErrorEvent
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecodedData
import org.freewheel.core.validation.Field
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Reducer-level integration tests for the [org.freewheel.core.validation.TelemetryValidator]
 * hook in `reduceDataReceived`.
 *
 * Verifies that out-of-bounds telemetry from a decoder produces
 * [ConnectionErrorEvent.TelemetryOutOfBounds] events via the public error log callback,
 * and that throttling state is carried correctly across frames in [WcmState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerValidationTest {

    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeDecoder: FakeDecoder
    private lateinit var fakeFactory: FakeDecoderFactory

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
        fakeDecoder = FakeDecoder()
        fakeFactory = FakeDecoderFactory(fakeDecoder)
    }

    private fun TestScope.createConnectedManager(): Pair<WheelConnectionManager, MutableList<ConnectionErrorEvent>> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = WheelConnectionManager(fakeBle, fakeFactory, backgroundScope, dispatcher)
        val captured = mutableListOf<ConnectionErrorEvent>()
        manager.errorLogCallback = { captured += it }

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.KINGSONG)
        runCurrent()
        fakeDecoder.ready = true
        return manager to captured
    }

    @Test
    fun `out-of-bounds pwm emits TelemetryOutOfBounds error event`() = runTest(timeout = 0.1.seconds) {
        val (manager, captured) = createConnectedManager()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(calculatedPwm = 1.5),  // 150 %
            identity = WheelIdentity(name = "KS-S18"),
        ))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val violations = captured.filterIsInstance<ConnectionErrorEvent.TelemetryOutOfBounds>()
        assertEquals(1, violations.size)
        assertEquals(Field.PwmPercent.name, violations.single().field)
        assertEquals(150.0, violations.single().value, absoluteTolerance = 0.001)
        assertEquals(0.0, violations.single().min)
        assertEquals(100.0, violations.single().max)
    }

    @Test
    fun `in-bounds telemetry emits no TelemetryOutOfBounds events`() = runTest(timeout = 0.1.seconds) {
        val (manager, captured) = createConnectedManager()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(
                speed = 5000,
                voltage = 8400,
                calculatedPwm = 0.5,
                batteryLevel = 75,
            ),
            identity = WheelIdentity(name = "KS-S18"),
        ))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(captured.none { it is ConnectionErrorEvent.TelemetryOutOfBounds })
    }

    @Test
    fun `repeated out-of-bounds within resample interval is throttled to one event`() = runTest(timeout = 0.1.seconds) {
        val (manager, captured) = createConnectedManager()

        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = TelemetryState(voltage = 50_000),  // 500 V
            identity = WheelIdentity(name = "KS-S18"),
        ))

        // Three frames in rapid succession — should produce only one violation event.
        manager.onDataReceived(byteArrayOf(0x01))
        manager.onDataReceived(byteArrayOf(0x01))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val violations = captured.filterIsInstance<ConnectionErrorEvent.TelemetryOutOfBounds>()
        assertEquals(1, violations.size, "Should edge-trigger once and throttle the rest")
        assertEquals(Field.VoltageV.name, violations.single().field)
    }

    @Test
    fun `decode with null telemetry does not crash or emit violations`() = runTest(timeout = 0.1.seconds) {
        val (manager, captured) = createConnectedManager()

        // Some decoder frames produce identity or BMS updates without telemetry.
        fakeDecoder.decodeResult = DecodeResult.Success(DecodedData(
            telemetry = null,
            identity = WheelIdentity(name = "KS-S18"),
        ))
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        assertTrue(captured.none { it is ConnectionErrorEvent.TelemetryOutOfBounds })
    }
}
