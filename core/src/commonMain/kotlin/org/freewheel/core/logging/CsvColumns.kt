package org.freewheel.core.logging

/**
 * Shared column name constants for ride CSV files.
 *
 * Referenced by [CsvFormatter], [CsvParser], and [RideCsvEditor] to ensure
 * column names stay in sync across write and read paths.
 */
object CsvColumns {
    // Timestamp columns (always present)
    const val DATE = "date"
    const val TIME = "time"

    // GPS columns (present when GPS logging is enabled)
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val GPS_SPEED = "gps_speed"
    const val GPS_ALT = "gps_alt"
    const val GPS_HEADING = "gps_heading"
    const val GPS_DISTANCE = "gps_distance"

    // Telemetry columns (always present)
    const val SPEED = "speed"
    const val VOLTAGE = "voltage"
    const val PHASE_CURRENT = "phase_current"
    const val CURRENT = "current"
    const val POWER = "power"
    const val TORQUE = "torque"
    const val PWM = "pwm"
    const val BATTERY_LEVEL = "battery_level"
    const val DISTANCE = "distance"
    const val TOTAL_DISTANCE = "totaldistance"
    const val SYSTEM_TEMP = "system_temp"
    const val TEMP2 = "temp2"
    const val TILT = "tilt"
    const val ROLL = "roll"
    const val MODE = "mode"
    const val ALERT = "alert"
}
