package org.freewheel.core.domain.dashboard

/**
 * Classification of a metric's physical unit for conversion purposes.
 * Used by [DisplayUtils] to determine which conversion to apply.
 */
enum class UnitCategory {
    SPEED,        // km/h <-> mph
    DISTANCE,     // km <-> mi
    TEMPERATURE,  // °C <-> °F
    ANGLE,        // degrees (no conversion)
    PERCENTAGE,   // % (no conversion)
    POWER,        // W (no conversion)
    CURRENT,      // A (no conversion)
    VOLTAGE,      // V (no conversion)
    NONE          // dimensionless or status-type (e.g., Fan)
}
