package org.freewheel.core.validation

import org.freewheel.core.domain.telemetry.TelemetryState

/**
 * Validates telemetry values against bounds that are physically or representationally
 * impossible — not normal-ride bounds.
 *
 * The goal is to catch decoder bugs (sign-extension mistakes, byte-order swaps,
 * scale-factor errors, uninitialized garbage) without false-firing on real rides
 * — including crashes, face-plants, and offroad cutout angles.
 *
 * Bounds are intentionally wide. A real ride that trips one of these is, by definition,
 * also evidence of a decoder bug.
 */
object TelemetryValidator {

    /** While a field stays out of bounds, log at most once per this interval. */
    internal const val RESAMPLE_INTERVAL_MS: Long = 5_000L

    /**
     * Check [telemetry] against the bounds table.
     *
     * Returns the updated throttle state plus the violations that should actually
     * be logged this frame (edge-triggered, with periodic resampling while still
     * out of bounds so a wedged sensor doesn't spam the log).
     */
    fun validate(
        telemetry: TelemetryState,
        throttleState: TelemetryThrottleState,
        nowMs: Long,
    ): ValidationResult {
        val updated = throttleState.lastLogged.toMutableMap()
        val toLog = mutableListOf<TelemetryViolation>()
        for (field in Field.all) {
            val value = field.extract(telemetry)
            val inBounds = value in field.bound.min..field.bound.max
            val lastLogged = updated[field]
            when {
                inBounds && lastLogged != null -> {
                    // Returned to bounds — clear so we get a fresh edge next time.
                    updated.remove(field)
                }
                inBounds -> {
                    // Still in bounds, nothing to do.
                }
                lastLogged == null -> {
                    // Edge: just went out of bounds.
                    updated[field] = nowMs
                    toLog += TelemetryViolation(field, value, field.bound, nowMs)
                }
                nowMs - lastLogged >= RESAMPLE_INTERVAL_MS -> {
                    // Still out of bounds and resample interval has elapsed.
                    updated[field] = nowMs
                    toLog += TelemetryViolation(field, value, field.bound, nowMs)
                }
                else -> {
                    // Still out of bounds, but throttled — drop.
                }
            }
        }
        return ValidationResult(
            newThrottleState = TelemetryThrottleState(updated.toMap()),
            violations = toLog,
        )
    }
}

/** Output of a validation pass. */
data class ValidationResult(
    val newThrottleState: TelemetryThrottleState,
    val violations: List<TelemetryViolation>,
)

/**
 * Per-field timestamp of the last logged violation, carried in [org.freewheel.core.service.WcmState]
 * across frames so the reducer remains pure.
 *
 * - Field absent → currently in bounds, will fire on the next out-of-bounds reading.
 * - Field present → still out of bounds; value is the last log time, used for resample throttling.
 */
data class TelemetryThrottleState(
    val lastLogged: Map<Field, Long> = emptyMap(),
)

/** A single field that exceeded its impossible-value bound. */
data class TelemetryViolation(
    val field: Field,
    val value: Double,
    val bound: Bound,
    val timestampMs: Long,
)

/** Inclusive numeric range. */
data class Bound(val min: Double, val max: Double)

/**
 * Typed field reference. Each variant carries its bound and an extractor so callers
 * never reach into [TelemetryState] with a stringly-typed field name.
 *
 * **Adding a field:** add the `data object` AND add it to [Field.all], or it will
 * silently be skipped by the validator. The matching test in `TelemetryValidatorTest`
 * asserts the list is complete.
 */
sealed class Field {
    abstract val name: String
    abstract val bound: Bound
    abstract fun extract(state: TelemetryState): Double

    data object PwmPercent : Field() {
        override val name = "pwm_percent"
        override val bound = Bound(0.0, 100.0)
        override fun extract(state: TelemetryState): Double = state.calculatedPwm * 100.0
    }

    data object BatteryLevel : Field() {
        override val name = "battery_level"
        override val bound = Bound(0.0, 100.0)
        override fun extract(state: TelemetryState): Double = state.batteryLevel.toDouble()
    }

    data object SpeedKmh : Field() {
        override val name = "speed_kmh"
        override val bound = Bound(-100.0, 250.0)
        override fun extract(state: TelemetryState): Double = state.speedKmh
    }

    data object VoltageV : Field() {
        override val name = "voltage_v"
        override val bound = Bound(0.0, 300.0)
        override fun extract(state: TelemetryState): Double = state.voltageV
    }

    data object CurrentA : Field() {
        override val name = "current_a"
        override val bound = Bound(-1000.0, 1000.0)
        override fun extract(state: TelemetryState): Double = state.currentA
    }

    data object PhaseCurrentA : Field() {
        override val name = "phase_current_a"
        override val bound = Bound(-2000.0, 2000.0)
        override fun extract(state: TelemetryState): Double = state.phaseCurrentA
    }

    data object TemperatureC : Field() {
        override val name = "temperature_c"
        override val bound = Bound(-50.0, 200.0)
        override fun extract(state: TelemetryState): Double = state.temperatureC.toDouble()
    }

    data object PitchAngle : Field() {
        override val name = "pitch_angle"
        override val bound = Bound(-120.0, 120.0)
        override fun extract(state: TelemetryState): Double = state.angle
    }

    data object RollAngle : Field() {
        override val name = "roll_angle"
        override val bound = Bound(-120.0, 120.0)
        override fun extract(state: TelemetryState): Double = state.roll
    }

    companion object {
        /**
         * All fields the validator checks. Order is stable for deterministic test
         * assertions and CSV output.
         */
        val all: List<Field> = listOf(
            PwmPercent,
            BatteryLevel,
            SpeedKmh,
            VoltageV,
            CurrentA,
            PhaseCurrentA,
            TemperatureC,
            PitchAngle,
            RollAngle,
        )
    }
}
