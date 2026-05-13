package org.freewheel.core.service

import org.freewheel.core.domain.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lock the [powerOffBlockReason] safety gate against the official Leaperkim
 * app's HomepageFragment shutdown rule (decompiled v1.4.8 lines 192–199):
 *
 *   if (carData != null && (speed > 0 || isCharging)) showToast() else send.
 *
 * The FreeWheel adaptation diverges in one place: telemetry == null returns
 * null (safe) instead of silently dropping the click — see the predicate's
 * KDoc for the rationale.
 */
class PowerOffSafetyTest {

    @Test
    fun `null telemetry is treated as safe`() {
        assertNull(powerOffBlockReason(null))
    }

    @Test
    fun `stationary not-charging telemetry is safe`() {
        val telemetry = TelemetryState(speed = 0, chargingStatus = 0)
        assertNull(powerOffBlockReason(telemetry))
    }

    @Test
    fun `non-zero speed blocks with WHEEL_MOVING`() {
        val telemetry = TelemetryState(speed = 1500, chargingStatus = 0) // 15 km/h
        assertEquals(PowerOffOutcome.Reason.WHEEL_MOVING, powerOffBlockReason(telemetry))
    }

    @Test
    fun `negative speed (reverse) also blocks with WHEEL_MOVING`() {
        // Some decoders pass through signed speed when gotwayNegative is set.
        // The gate must treat any non-zero magnitude as moving.
        val telemetry = TelemetryState(speed = -500, chargingStatus = 0)
        assertEquals(PowerOffOutcome.Reason.WHEEL_MOVING, powerOffBlockReason(telemetry))
    }

    @Test
    fun `charging blocks with WHEEL_CHARGING when stationary`() {
        val telemetry = TelemetryState(speed = 0, chargingStatus = 1)
        assertEquals(PowerOffOutcome.Reason.WHEEL_CHARGING, powerOffBlockReason(telemetry))
    }

    @Test
    fun `moving wins over charging when both true`() {
        // Mirrors the app: speed check fires first in the if-chain.
        val telemetry = TelemetryState(speed = 100, chargingStatus = 1)
        assertEquals(PowerOffOutcome.Reason.WHEEL_MOVING, powerOffBlockReason(telemetry))
    }
}
