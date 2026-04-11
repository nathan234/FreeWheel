package org.freewheel.core.protocol.fixtures

import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.decoderStateFrom
import org.freewheel.core.protocol.hexToByteArray
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Declarative golden-frame fixture for decoder tests.
 *
 * A fixture pairs a sequence of raw BLE frames (hex strings) with the expected
 * accumulated state after the reducer folds each frame's [org.freewheel.core.protocol.DecodedData]
 * into the running [DecoderState] — the same path used in production by
 * [org.freewheel.core.service.WheelConnectionManager].
 *
 * Only fields explicitly set in [expect] are asserted; unspecified fields are
 * ignored, which keeps fixtures stable when unrelated state fields are added.
 *
 * Fixtures live next to this file under `fixtures/` as plain-text Kotlin `object`
 * declarations so they are trivially grep-able and diff-friendly. Each decoder
 * gets one fixture file; the test file just enumerates which fixtures to run.
 *
 * See [GotwayFixtures], [VeteranFixtures], [KingsongFixtures] for examples.
 */
internal data class DecoderFixture(
    val name: String,
    val description: String = "",
    val frames: List<String>,
    val config: DecoderConfig = DecoderConfig(),
    val expect: Expected,
) {
    /**
     * Partial expectations applied after all [frames] have been fed through the decoder.
     * Each non-null field is asserted; null fields are ignored.
     */
    internal data class Expected(
        val lastResult: ResultKind? = null,
        val telemetry: TelemetryExpect? = null,
        val identity: IdentityExpect? = null,
        val settings: WheelSettings? = null,
    )

    internal data class TelemetryExpect(
        val speed: Int? = null,
        val voltage: Int? = null,
        val current: Int? = null,
        val phaseCurrent: Int? = null,
        val power: Int? = null,
        val temperature: Int? = null,
        val temperature2: Int? = null,
        val batteryLevel: Int? = null,
        val output: Int? = null,
        val wheelDistance: Long? = null,
        val totalDistance: Long? = null,
        val topSpeed: Int? = null,
        val rideTime: Int? = null,
    )

    internal data class IdentityExpect(
        val wheelType: WheelType? = null,
        val model: String? = null,
        val name: String? = null,
        val serialNumber: String? = null,
        val version: String? = null,
    )

    internal enum class ResultKind { BUFFERING, SUCCESS, UNHANDLED }
}

/**
 * Feed [fixture] through this decoder and assert the expected state.
 *
 * The caller is responsible for passing a freshly constructed decoder — fixtures
 * do not share state across runs.
 */
internal fun WheelDecoder.runFixture(fixture: DecoderFixture) {
    var state = DecoderState()
    var lastResult: DecodeResult? = null

    for ((index, hex) in fixture.frames.withIndex()) {
        val bytes = hex.hexToByteArray()
        lastResult = try {
            decode(bytes, state, fixture.config)
        } catch (e: Throwable) {
            fail("fixture '${fixture.name}' frame $index threw ${e::class.simpleName}: ${e.message}")
        }
        if (lastResult is DecodeResult.Success) {
            state = lastResult.data.decoderStateFrom(state)
        }
    }

    assertExpected(fixture, state, lastResult)
}

private fun assertExpected(fixture: DecoderFixture, state: DecoderState, lastResult: DecodeResult?) {
    val label = "fixture '${fixture.name}'"

    fixture.expect.lastResult?.let { expectedKind ->
        val actualKind = when (lastResult) {
            is DecodeResult.Success -> DecoderFixture.ResultKind.SUCCESS
            is DecodeResult.Buffering -> DecoderFixture.ResultKind.BUFFERING
            is DecodeResult.Unhandled -> DecoderFixture.ResultKind.UNHANDLED
            null -> fail("$label produced no decode result (empty frame list)")
        }
        assertEquals(expectedKind, actualKind, "$label: last decode result kind")
    }

    fixture.expect.telemetry?.let { t ->
        val a = state.telemetry
        t.speed?.let { assertEquals(it, a.speed, "$label: telemetry.speed") }
        t.voltage?.let { assertEquals(it, a.voltage, "$label: telemetry.voltage") }
        t.current?.let { assertEquals(it, a.current, "$label: telemetry.current") }
        t.phaseCurrent?.let { assertEquals(it, a.phaseCurrent, "$label: telemetry.phaseCurrent") }
        t.power?.let { assertEquals(it, a.power, "$label: telemetry.power") }
        t.temperature?.let { assertEquals(it, a.temperature, "$label: telemetry.temperature") }
        t.temperature2?.let { assertEquals(it, a.temperature2, "$label: telemetry.temperature2") }
        t.batteryLevel?.let { assertEquals(it, a.batteryLevel, "$label: telemetry.batteryLevel") }
        t.output?.let { assertEquals(it, a.output, "$label: telemetry.output") }
        t.wheelDistance?.let { assertEquals(it, a.wheelDistance, "$label: telemetry.wheelDistance") }
        t.totalDistance?.let { assertEquals(it, a.totalDistance, "$label: telemetry.totalDistance") }
        t.topSpeed?.let { assertEquals(it, a.topSpeed, "$label: telemetry.topSpeed") }
        t.rideTime?.let { assertEquals(it, a.rideTime, "$label: telemetry.rideTime") }
    }

    fixture.expect.identity?.let { i ->
        val a = state.identity
        i.wheelType?.let { assertEquals(it, a.wheelType, "$label: identity.wheelType") }
        i.model?.let { assertEquals(it, a.model, "$label: identity.model") }
        i.name?.let { assertEquals(it, a.name, "$label: identity.name") }
        i.serialNumber?.let { assertEquals(it, a.serialNumber, "$label: identity.serialNumber") }
        i.version?.let { assertEquals(it, a.version, "$label: identity.version") }
    }

    fixture.expect.settings?.let { expected ->
        assertEquals(expected, state.settings, "$label: settings")
    }
}
