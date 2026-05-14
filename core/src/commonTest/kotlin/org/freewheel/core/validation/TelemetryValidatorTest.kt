package org.freewheel.core.validation

import org.freewheel.core.domain.telemetry.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryValidatorTest {

    // ==================== Rule table ====================

    @Test
    fun `in-bounds telemetry produces no violations`() {
        val tel = TelemetryState(
            speed = 5000,                 // 50 km/h
            voltage = 8400,               // 84 V
            current = 5000,               // 50 A
            phaseCurrent = 10000,         // 100 A
            temperature = 4500,           // 45 C
            batteryLevel = 75,
            calculatedPwm = 0.65,         // 65 %
            angle = 15.0,
            roll = 5.0,
        )

        val result = TelemetryValidator.validate(
            telemetry = tel,
            throttleState = TelemetryThrottleState(),
            nowMs = 1_000,
        )

        assertTrue(result.violations.isEmpty())
        assertTrue(result.newThrottleState.lastLogged.isEmpty())
    }

    @Test
    fun `pwm above 100 percent is flagged`() {
        val tel = TelemetryState(calculatedPwm = 1.27)  // 127 %
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)

        val violation = result.violations.single()
        assertEquals(Field.PwmPercent, violation.field)
        assertEquals(127.0, violation.value, absoluteTolerance = 0.001)
        assertEquals(Bound(0.0, 100.0), violation.bound)
    }

    @Test
    fun `pwm below 0 percent is flagged`() {
        val tel = TelemetryState(calculatedPwm = -0.05)  // -5 %
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.PwmPercent, result.violations.single().field)
    }

    @Test
    fun `battery level above 100 is flagged`() {
        val tel = TelemetryState(batteryLevel = 127)
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.BatteryLevel, result.violations.single().field)
    }

    @Test
    fun `speed above 250 kmh is flagged`() {
        val tel = TelemetryState(speed = 30_000)  // 300 km/h
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.SpeedKmh, result.violations.single().field)
    }

    @Test
    fun `speed at -50 kmh is in bounds`() {
        // Negative speed represents reverse/rollback — legitimate for some decoders.
        val tel = TelemetryState(speed = -5_000)  // -50 km/h
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertTrue(result.violations.none { it.field == Field.SpeedKmh })
    }

    @Test
    fun `speed below -100 kmh is flagged`() {
        val tel = TelemetryState(speed = -15_000)  // -150 km/h
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.SpeedKmh, result.violations.single().field)
    }

    @Test
    fun `voltage above 300V is flagged`() {
        val tel = TelemetryState(voltage = 40_000)  // 400 V
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.VoltageV, result.violations.single().field)
    }

    @Test
    fun `voltage at 252V XMax nominal is in bounds`() {
        val tel = TelemetryState(voltage = 25_200)  // Begode XMax 60S
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `current above 1000A is flagged`() {
        val tel = TelemetryState(current = 200_000)  // 2000 A — bogus
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.CurrentA, result.violations.single().field)
    }

    @Test
    fun `phase current at 500A is in bounds`() {
        // Real top-wheel phase currents during hard acceleration can hit several hundred A.
        val tel = TelemetryState(phaseCurrent = 50_000)  // 500 A
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertTrue(result.violations.none { it.field == Field.PhaseCurrentA })
    }

    @Test
    fun `phase current above 2000A is flagged`() {
        val tel = TelemetryState(phaseCurrent = 300_000)  // 3000 A
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.PhaseCurrentA, result.violations.single().field)
    }

    @Test
    fun `temperature above 200C is flagged`() {
        val tel = TelemetryState(temperature = 25_000)  // 250 C
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.TemperatureC, result.violations.single().field)
    }

    @Test
    fun `temperature below -50C is flagged`() {
        val tel = TelemetryState(temperature = -6_000)  // -60 C
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.TemperatureC, result.violations.single().field)
    }

    @Test
    fun `angle at 90 degrees face-plant is in bounds`() {
        val tel = TelemetryState(angle = 90.0)
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertTrue(result.violations.none { it.field == Field.PitchAngle })
    }

    @Test
    fun `angle above 120 degrees is flagged`() {
        val tel = TelemetryState(angle = 180.0)  // upside-down decoder bug
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.PitchAngle, result.violations.single().field)
    }

    @Test
    fun `roll below -120 degrees is flagged`() {
        val tel = TelemetryState(roll = -150.0)
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)
        assertEquals(Field.RollAngle, result.violations.single().field)
    }

    @Test
    fun `multiple simultaneous violations all reported on the first frame`() {
        val tel = TelemetryState(
            calculatedPwm = 2.0,    // 200%
            voltage = 50_000,       // 500 V
            angle = 200.0,
        )
        val result = TelemetryValidator.validate(tel, TelemetryThrottleState(), 1_000)

        val fields = result.violations.map { it.field }.toSet()
        assertEquals(setOf(Field.PwmPercent, Field.VoltageV, Field.PitchAngle), fields)
    }

    @Test
    fun `Field all contains every sealed subclass`() {
        // Guard against adding a new Field and forgetting to register it.
        // If a new `data object` is added under Field but not to `all`, this will fail
        // because the count won't match the sealed class's subclass set.
        val names = Field.all.map { it.name }
        assertEquals(names.distinct().size, names.size, "Field.all has duplicates")
        assertEquals(9, Field.all.size, "Field.all should have 9 entries — update this test when adding a field")
    }

    // ==================== Throttling ====================

    @Test
    fun `repeated out-of-bounds within resample interval is throttled`() {
        val tel = TelemetryState(calculatedPwm = 1.5)  // 150%

        // First frame: edge — logs.
        val r1 = TelemetryValidator.validate(tel, TelemetryThrottleState(), nowMs = 1_000)
        assertEquals(1, r1.violations.size)

        // Second frame 1 ms later: still out of bounds, throttled.
        val r2 = TelemetryValidator.validate(tel, r1.newThrottleState, nowMs = 1_001)
        assertTrue(r2.violations.isEmpty())

        // Throttle state should still mark this field as last logged at 1_000.
        assertEquals(1_000L, r2.newThrottleState.lastLogged[Field.PwmPercent])
    }

    @Test
    fun `periodic resample fires after interval elapses`() {
        val tel = TelemetryState(calculatedPwm = 1.5)

        val r1 = TelemetryValidator.validate(tel, TelemetryThrottleState(), nowMs = 0)
        assertEquals(1, r1.violations.size)

        // Just under the resample interval — still throttled.
        val r2 = TelemetryValidator.validate(
            tel,
            r1.newThrottleState,
            nowMs = TelemetryValidator.RESAMPLE_INTERVAL_MS - 1,
        )
        assertTrue(r2.violations.isEmpty())

        // At the resample interval — fires again.
        val r3 = TelemetryValidator.validate(
            tel,
            r2.newThrottleState,
            nowMs = TelemetryValidator.RESAMPLE_INTERVAL_MS,
        )
        assertEquals(1, r3.violations.size)
    }

    @Test
    fun `returning to bounds clears throttle state so next edge fires`() {
        val bad = TelemetryState(calculatedPwm = 1.5)
        val good = TelemetryState(calculatedPwm = 0.5)

        val r1 = TelemetryValidator.validate(bad, TelemetryThrottleState(), nowMs = 0)
        assertEquals(1, r1.violations.size)

        // Recovers to in-bounds — clears throttle state for the field.
        val r2 = TelemetryValidator.validate(good, r1.newThrottleState, nowMs = 100)
        assertTrue(r2.violations.isEmpty())
        assertNull(r2.newThrottleState.lastLogged[Field.PwmPercent])

        // Goes bad again 50 ms later (well under the resample interval) —
        // edge-triggers a fresh violation because throttle state was cleared.
        val r3 = TelemetryValidator.validate(bad, r2.newThrottleState, nowMs = 150)
        assertEquals(1, r3.violations.size)
    }

    @Test
    fun `unrelated fields do not share throttle state`() {
        val badPwm = TelemetryState(calculatedPwm = 1.5)
        val badPwmAndVoltage = TelemetryState(calculatedPwm = 1.5, voltage = 50_000)

        val r1 = TelemetryValidator.validate(badPwm, TelemetryThrottleState(), nowMs = 0)
        assertEquals(setOf(Field.PwmPercent), r1.violations.map { it.field }.toSet())

        // Voltage was in bounds on r1 — now it goes bad on the next frame,
        // which is well under the resample interval. PWM should stay throttled
        // (still bad since last log), but voltage should edge-fire.
        val r2 = TelemetryValidator.validate(badPwmAndVoltage, r1.newThrottleState, nowMs = 1)
        assertEquals(setOf(Field.VoltageV), r2.violations.map { it.field }.toSet())
    }
}
