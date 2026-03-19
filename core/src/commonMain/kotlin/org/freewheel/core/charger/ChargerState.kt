package org.freewheel.core.charger

/**
 * Immutable state of an HW Charger (Roger/Pidzoom).
 * All values are native floats — no Int×100 scaling like TelemetryState.
 * Decoders return currentState.copy(field = newValue).
 */
data class ChargerState(
    // DC Output (to wheel) — from Status frame (cmd 0x06)
    val dcVoltage: Float = 0f,
    val dcCurrent: Float = 0f,
    // AC Input (from mains) — from Status frame (cmd 0x06)
    val acVoltage: Float = 0f,
    val acCurrent: Float = 0f,
    val acFrequency: Float = 0f,
    // Thermal — from Status frame (cmd 0x06)
    val temperature1: Float = 0f,
    val temperature2: Float = 0f,
    // Efficiency/limits — from Status frame (cmd 0x06)
    val currentLimitingPoint: Float = 0f,
    val efficiency: Float = 0f,
    // Output state — from Status frame byte 38, inverted (0=ON)
    val isOutputEnabled: Boolean = false,
    // Setpoints readback — from Setpoints frame (cmd 0x05)
    val targetVoltage: Float = 0f,
    val targetCurrent: Float = 0f,
    // Identity — from Firmware frame (cmd 0x01)
    val firmwareVersion: String = "",
    // Auth status
    val isAuthenticated: Boolean = false,
    // Timestamp
    val lastUpdateMs: Long = 0
) {
    /** Calculated: dcVoltage * dcCurrent */
    val dcPower: Float get() = dcVoltage * dcCurrent

    /** Calculated: acVoltage * acCurrent */
    val acPower: Float get() = acVoltage * acCurrent

    /** Calculated: dcCurrent > 0.1A */
    val isCharging: Boolean get() = dcCurrent > 0.1f

    companion object {
        /** Swift-friendly factory (KMP data class default params not accessible from Swift). */
        fun empty(): ChargerState = ChargerState()
    }
}
