package org.freewheel.core.logging

import org.freewheel.core.domain.TelemetryState

/**
 * Formats ride data as CSV rows matching the legacy WheelLog format.
 *
 * Constructed with a GPS mode that is captured once and applied consistently
 * to both the [header] and every [row] call — making header/row column
 * mismatch unrepresentable.
 *
 * Column order (without GPS):
 * date,time,speed,voltage,phase_current,current,power,torque,pwm,
 * battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert
 *
 * With GPS, six columns are inserted after time:
 * latitude,longitude,gps_speed,gps_alt,gps_heading,gps_distance
 */
class CsvFormatter private constructor(private val includeGps: Boolean) {

    companion object {
        fun create(includeGps: Boolean): CsvFormatter = CsvFormatter(includeGps)
    }

    /** CSV header line. Column set is fixed at construction. */
    val header: String = buildHeader()

    /**
     * Returns a single CSV data row.
     *
     * @param dateTime Pre-formatted timestamp string as "yyyy-MM-dd,HH:mm:ss.SSS".
     * @param telemetry Current telemetry state.
     * @param modeStr Ride mode string (from WheelIdentity).
     * @param tripDistance Trip distance in meters (totalDistance - startTotalDistance).
     * @param gps Optional GPS location data. Ignored when this formatter was created without GPS.
     */
    fun row(
        dateTime: String,
        telemetry: TelemetryState,
        modeStr: String = "",
        tripDistance: Int,
        gps: GpsLocation? = null,
    ): String {
        val gpsStr = if (includeGps && gps != null) {
            "${gps.latitude},${gps.longitude},${gps.speedKmh},${gps.altitude},${gps.bearing},${formatFixed(gps.cumulativeDistance, 0)},"
        } else if (includeGps) {
            ",,,,,,"
        } else ""

        return buildString {
            // date,time (dateTime contains the comma)
            append(dateTime)
            append(',')
            // GPS columns (empty string or data ending with comma)
            append(gpsStr)
            // Telemetry columns
            append(formatFixed(telemetry.speedKmh, 2))
            append(',')
            append(formatFixed(telemetry.voltageV, 2))
            append(',')
            append(formatFixed(telemetry.phaseCurrentA, 2))
            append(',')
            append(formatFixed(telemetry.currentA, 2))
            append(',')
            append(formatFixed(telemetry.powerW, 2))
            append(',')
            append(formatFixed(telemetry.torque, 2))
            append(',')
            append(formatFixed(telemetry.pwmPercent, 2))
            append(',')
            append(telemetry.batteryLevel)
            append(',')
            append(tripDistance)
            append(',')
            append(telemetry.totalDistance)
            append(',')
            append(telemetry.temperatureC)
            append(',')
            append(telemetry.temperature2C)
            append(',')
            append(formatFixed(telemetry.angle, 2))
            append(',')
            append(formatFixed(telemetry.roll, 2))
            append(',')
            append(modeStr)
            append(',')
            append(telemetry.alert)
        }
    }

    private fun buildHeader(): String {
        val c = CsvColumns
        val telemetry = listOf(
            c.SPEED, c.VOLTAGE, c.PHASE_CURRENT, c.CURRENT, c.POWER, c.TORQUE, c.PWM,
            c.BATTERY_LEVEL, c.DISTANCE, c.TOTAL_DISTANCE, c.SYSTEM_TEMP, c.TEMP2,
            c.TILT, c.ROLL, c.MODE, c.ALERT
        ).joinToString(",")

        val gps = if (includeGps) {
            listOf(c.LATITUDE, c.LONGITUDE, c.GPS_SPEED, c.GPS_ALT, c.GPS_HEADING, c.GPS_DISTANCE)
                .joinToString(",", postfix = ",")
        } else ""

        return "${c.DATE},${c.TIME},${gps}$telemetry"
    }
}
