package org.freewheel.utils

import org.freewheel.core.domain.WheelState
import org.freewheel.core.logging.CsvFormatter
import org.freewheel.core.logging.GpsLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Parity tests comparing the legacy LoggingService CSV format string against KMP CsvFormatter.
 * Each test applies the exact format string from LoggingService.kt:286 to known values,
 * then generates a KMP CSV row from an equivalent WheelState, and asserts they match.
 *
 * No WheelData instance is needed — we use known values in both the legacy format string
 * and the KMP WheelState, which proves the format is identical.
 */
class CsvParityTest {

    /**
     * Generate a legacy-format CSV row using the exact format string from LoggingService.
     * Parameters match WheelData getters:
     * speedDouble, voltageDouble, phaseCurrentDouble, currentDouble, powerDouble,
     * torque, calculatedPwm, batteryLevel, distance(trip), totalDistance,
     * temperature, temperature2, angle, roll, modeStr, alert.
     */
    private fun legacyRow(
        dateTime: String,
        gpsStr: String,
        speed: Double, voltage: Double, phaseCurrent: Double, current: Double,
        power: Double, torque: Double, pwm: Double,
        battery: Int, distance: Int, totalDistance: Long,
        temp: Int, temp2: Int, angle: Double, roll: Double,
        mode: String, alert: String
    ): String = String.format(
        Locale.US,
        "%s,%s%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%s",
        dateTime, gpsStr,
        speed, voltage, phaseCurrent, current, power, torque, pwm,
        battery, distance, totalDistance, temp, temp2, angle, roll,
        mode, alert
    )

    // --- Header tests ---

    @Test
    fun `CSV header without GPS matches legacy`() {
        val legacyHeader =
            "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
            "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"
        assertThat(CsvFormatter.header(includeGps = false)).isEqualTo(legacyHeader)
    }

    @Test
    fun `CSV header with GPS matches legacy`() {
        val legacyHeader =
            "date,time,latitude,longitude,gps_speed,gps_alt,gps_heading,gps_distance," +
            "speed,voltage,phase_current,current,power,torque,pwm," +
            "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"
        assertThat(CsvFormatter.header(includeGps = true)).isEqualTo(legacyHeader)
    }

    // --- Data row tests ---

    @Test
    fun `CSV row with typical values matches legacy format`() {
        val dt = "2024-01-15,14:30:45.123"

        val legacy = legacyRow(
            dt, "",
            speed = 5.15, voltage = 65.05, phaseCurrent = 2.15, current = 3.00,
            power = 195.00, torque = 1.23, pwm = 12.50,
            battery = 62, distance = 1234, totalDistance = 56789,
            temp = 35, temp2 = 28, angle = 1.50, roll = -0.30,
            mode = "0", alert = ""
        )

        val state = WheelState(
            speed = 515, voltage = 6505, phaseCurrent = 215, current = 300,
            power = 19500, torque = 1.23, calculatedPwm = 0.125,
            batteryLevel = 62, totalDistance = 56789,
            temperature = 3500, temperature2 = 2800,
            angle = 1.50, roll = -0.30, modeStr = "0", alert = ""
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 1234)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with zero values matches legacy format`() {
        val dt = "2024-06-01,00:00:00.000"

        val legacy = legacyRow(
            dt, "",
            speed = 0.0, voltage = 0.0, phaseCurrent = 0.0, current = 0.0,
            power = 0.0, torque = 0.0, pwm = 0.0,
            battery = 0, distance = 0, totalDistance = 0,
            temp = 0, temp2 = 0, angle = 0.0, roll = 0.0,
            mode = "", alert = ""
        )

        val state = WheelState()
        val kmp = CsvFormatter.row(dt, state, tripDistance = 0)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with max speed values matches legacy format`() {
        val dt = "2024-12-31,23:59:59.999"

        val legacy = legacyRow(
            dt, "",
            speed = 50.00, voltage = 100.80, phaseCurrent = 55.00, current = 42.00,
            power = 4233.60, torque = 15.67, pwm = 95.00,
            battery = 100, distance = 99999, totalDistance = 999999,
            temp = 55, temp2 = 42, angle = 5.75, roll = 3.20,
            mode = "Comfort", alert = "Speed Warning"
        )

        val state = WheelState(
            speed = 5000, voltage = 10080, phaseCurrent = 5500, current = 4200,
            power = 423360, torque = 15.67, calculatedPwm = 0.95,
            batteryLevel = 100, totalDistance = 999999,
            temperature = 5500, temperature2 = 4200,
            angle = 5.75, roll = 3.20, modeStr = "Comfort", alert = "Speed Warning"
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 99999)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with negative current matches legacy format`() {
        val dt = "2024-03-15,10:20:30.456"

        val legacy = legacyRow(
            dt, "",
            speed = 12.00, voltage = 84.00, phaseCurrent = -15.00, current = -12.00,
            power = -100.80, torque = -2.50, pwm = 30.00,
            battery = 78, distance = 2345, totalDistance = 12345,
            temp = 32, temp2 = 29, angle = -1.20, roll = 0.80,
            mode = "1", alert = ""
        )

        val state = WheelState(
            speed = 1200, voltage = 8400, phaseCurrent = -1500, current = -1200,
            power = -10080, torque = -2.50, calculatedPwm = 0.30,
            batteryLevel = 78, totalDistance = 12345,
            temperature = 3200, temperature2 = 2900,
            angle = -1.20, roll = 0.80, modeStr = "1", alert = ""
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 2345)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with large total distance matches legacy format`() {
        val dt = "2024-07-04,12:00:00.000"

        val legacy = legacyRow(
            dt, "",
            speed = 25.00, voltage = 67.00, phaseCurrent = 8.00, current = 6.00,
            power = 402.00, torque = 0.0, pwm = 5.00,
            battery = 45, distance = 67890, totalDistance = 1234567890,
            temp = 30, temp2 = 25, angle = 0.0, roll = 0.0,
            mode = "2", alert = ""
        )

        val state = WheelState(
            speed = 2500, voltage = 6700, phaseCurrent = 800, current = 600,
            power = 40200, torque = 0.0, calculatedPwm = 0.05,
            batteryLevel = 45, totalDistance = 1234567890,
            temperature = 3000, temperature2 = 2500,
            angle = 0.0, roll = 0.0, modeStr = "2", alert = ""
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 67890)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with GPS data matches legacy format`() {
        val dt = "2024-08-20,16:45:30.789"

        // Legacy GPS formatting (LoggingService lines 258-282)
        val lat = 37.7749
        val lon = -122.4194
        val gpsSpeed = 28.8
        val gpsAlt = 30.5
        val gpsBearing = 180.0
        val gpsDist = 1234.0
        val locationDataString = String.format(
            Locale.US, "%s,%s,%s,%s,%s,%.0f,",
            lat.toString(), lon.toString(),
            gpsSpeed.toString(), gpsAlt.toString(),
            gpsBearing.toString(), gpsDist
        )

        val legacy = legacyRow(
            dt, locationDataString,
            speed = 30.00, voltage = 72.00, phaseCurrent = 10.00, current = 8.00,
            power = 576.00, torque = 0.50, pwm = 20.00,
            battery = 55, distance = 2000, totalDistance = 50000,
            temp = 38, temp2 = 31, angle = 2.10, roll = -0.50,
            mode = "0", alert = ""
        )

        val state = WheelState(
            speed = 3000, voltage = 7200, phaseCurrent = 1000, current = 800,
            power = 57600, torque = 0.50, calculatedPwm = 0.20,
            batteryLevel = 55, totalDistance = 50000,
            temperature = 3800, temperature2 = 3100,
            angle = 2.10, roll = -0.50, modeStr = "0", alert = ""
        )

        val gps = GpsLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 28.8,
            altitude = 30.5,
            bearing = 180.0,
            cumulativeDistance = 1234.0
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 2000, gps = gps)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row with alert containing pipe separators matches legacy format`() {
        val dt = "2024-09-10,08:15:00.000"

        val legacy = legacyRow(
            dt, "",
            speed = 45.00, voltage = 60.00, phaseCurrent = 30.00, current = 25.00,
            power = 1500.00, torque = 0.0, pwm = 80.00,
            battery = 15, distance = 5000, totalDistance = 100000,
            temp = 60, temp2 = 50, angle = 0.0, roll = 0.0,
            mode = "Hard", alert = "Speed1 | Temp"
        )

        val state = WheelState(
            speed = 4500, voltage = 6000, phaseCurrent = 3000, current = 2500,
            power = 150000, torque = 0.0, calculatedPwm = 0.80,
            batteryLevel = 15, totalDistance = 100000,
            temperature = 6000, temperature2 = 5000,
            angle = 0.0, roll = 0.0, modeStr = "Hard", alert = "Speed1 | Temp"
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 5000)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row fractional precision matches legacy exactly`() {
        val dt = "2024-02-29,11:11:11.111"

        val legacy = legacyRow(
            dt, "",
            speed = 12.34, voltage = 67.89, phaseCurrent = 9.99, current = 10.01,
            power = 678.23, torque = 3.456, pwm = 77.77,
            battery = 50, distance = 7777, totalDistance = 77777,
            temp = 33, temp2 = 22, angle = 12.34, roll = -56.78,
            mode = "Soft", alert = ""
        )

        val state = WheelState(
            speed = 1234, voltage = 6789, phaseCurrent = 999, current = 1001,
            power = 67823, torque = 3.456, calculatedPwm = 0.7777,
            batteryLevel = 50, totalDistance = 77777,
            temperature = 3300, temperature2 = 2200,
            angle = 12.34, roll = -56.78, modeStr = "Soft", alert = ""
        )

        val kmp = CsvFormatter.row(dt, state, tripDistance = 7777)
        assertThat(kmp).isEqualTo(legacy)
    }

    @Test
    fun `CSV row torque precision three decimals renders as two`() {
        // torque=3.456 should render as "3.46" with %.2f (rounds up)
        val dt = "2024-01-01,00:00:00.000"

        val legacy = legacyRow(
            dt, "",
            speed = 0.0, voltage = 0.0, phaseCurrent = 0.0, current = 0.0,
            power = 0.0, torque = 3.456, pwm = 0.0,
            battery = 0, distance = 0, totalDistance = 0,
            temp = 0, temp2 = 0, angle = 0.0, roll = 0.0,
            mode = "", alert = ""
        )

        val state = WheelState(torque = 3.456)
        val kmp = CsvFormatter.row(dt, state, tripDistance = 0)
        assertThat(kmp).isEqualTo(legacy)
    }
}
