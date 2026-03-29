package org.freewheel.core.logging

import org.freewheel.core.domain.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CsvFormatterTest {

    private val noGps = CsvFormatter.create(includeGps = false)
    private val withGps = CsvFormatter.create(includeGps = true)

    // ==================== Header ====================

    @Test
    fun `header without GPS has expected columns`() {
        assertEquals(
            "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
                "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert",
            noGps.header
        )
    }

    @Test
    fun `header with GPS inserts six GPS columns after time`() {
        val cols = withGps.header.split(",")
        assertEquals("date", cols[0])
        assertEquals("time", cols[1])
        // GPS columns
        assertEquals("latitude", cols[2])
        assertEquals("longitude", cols[3])
        assertEquals("gps_speed", cols[4])
        assertEquals("gps_alt", cols[5])
        assertEquals("gps_heading", cols[6])
        assertEquals("gps_distance", cols[7])
        // Telemetry continues
        assertEquals("speed", cols[8])
    }

    @Test
    fun `header with GPS has 24 columns`() {
        val cols = withGps.header.split(",")
        // 18 base + 6 GPS = 24
        assertEquals(24, cols.size)
    }

    @Test
    fun `header without GPS has 18 columns`() {
        val cols = noGps.header.split(",")
        assertEquals(18, cols.size)
    }

    // ==================== Row ====================

    @Test
    fun `row without GPS produces correct column count`() {
        val telemetry = TelemetryState()
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals(18, cols.size)
    }

    @Test
    fun `row with GPS produces correct column count`() {
        val telemetry = TelemetryState()
        val gps = GpsLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 25.0,
            altitude = 10.0,
            bearing = 180.0,
            cumulativeDistance = 500.0
        )
        val row = withGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0, gps = gps)
        val cols = row.split(",")
        assertEquals(24, cols.size)
    }

    @Test
    fun `row dateTime appears in first two columns`() {
        val telemetry = TelemetryState()
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("2024-01-15", cols[0])
        assertEquals("10:30:00.000", cols[1])
    }

    @Test
    fun `row encodes speed with 2 decimals`() {
        // speed=2500 -> 25.00 km/h
        val telemetry = TelemetryState(speed = 2500)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("25.00", cols[2]) // speed column (index 2 without GPS)
    }

    @Test
    fun `row encodes voltage with 2 decimals`() {
        // voltage=8400 -> 84.00 V
        val telemetry = TelemetryState(voltage = 8400)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("84.00", cols[3]) // voltage
    }

    @Test
    fun `row encodes phase current with 2 decimals`() {
        val telemetry = TelemetryState(phaseCurrent = 1550) // 15.50 A
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("15.50", cols[4])
    }

    @Test
    fun `row encodes current with 2 decimals`() {
        val telemetry = TelemetryState(current = 1200) // 12.00 A
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("12.00", cols[5])
    }

    @Test
    fun `row encodes power with 2 decimals`() {
        val telemetry = TelemetryState(power = 150000) // 1500.00 W
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("1500.00", cols[6])
    }

    @Test
    fun `row encodes torque with 2 decimals`() {
        val telemetry = TelemetryState(torque = 12.34)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("12.34", cols[7])
    }

    @Test
    fun `row encodes pwm with 2 decimals`() {
        // calculatedPwm=0.4567 -> pwmPercent = 45.67
        val telemetry = TelemetryState(calculatedPwm = 0.4567)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("45.67", cols[8])
    }

    @Test
    fun `row encodes battery level as integer`() {
        val telemetry = TelemetryState(batteryLevel = 85)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("85", cols[9])
    }

    @Test
    fun `row encodes trip distance as integer meters`() {
        val telemetry = TelemetryState()
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 1234)
        val cols = row.split(",")
        assertEquals("1234", cols[10]) // distance
    }

    @Test
    fun `row encodes total distance as long meters`() {
        val telemetry = TelemetryState(totalDistance = 999999)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("999999", cols[11]) // totaldistance
    }

    @Test
    fun `row encodes temperatures as integers`() {
        // temperature=3500 -> 35 °C, temperature2=2800 -> 28 °C
        val telemetry = TelemetryState(temperature = 3500, temperature2 = 2800)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("35", cols[12])  // system_temp
        assertEquals("28", cols[13])  // temp2
    }

    @Test
    fun `row encodes tilt and roll with 2 decimals`() {
        val telemetry = TelemetryState(angle = 2.5, roll = -1.3)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("2.50", cols[14])  // tilt
        assertEquals("-1.30", cols[15]) // roll
    }

    @Test
    fun `row encodes mode and alert strings`() {
        val telemetry = TelemetryState(alert = "")
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, modeStr = "Sport", tripDistance = 0)
        val cols = row.split(",")
        assertEquals("Sport", cols[16])
        assertEquals("", cols[17])
    }

    @Test
    fun `row with GPS includes location data`() {
        val telemetry = TelemetryState(speed = 2000)
        val gps = GpsLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 20.0,
            altitude = 50.5,
            bearing = 90.0,
            cumulativeDistance = 1234.0
        )
        val row = withGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 100, gps = gps)
        val cols = row.split(",")
        // GPS columns start at index 2
        assertEquals("37.7749", cols[2])
        assertEquals("-122.4194", cols[3])
        assertEquals("20.0", cols[4])
        assertEquals("50.5", cols[5])
        assertEquals("90.0", cols[6])
        assertEquals("1234", cols[7]) // GPS distance formatted with 0 decimals
        // Telemetry columns shifted by 6
        assertEquals("20.00", cols[8]) // speed
    }

    @Test
    fun `row without GPS omits location columns`() {
        val telemetry = TelemetryState()
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0, gps = null)
        assertFalse(row.contains("37.7749"))
    }

    @Test
    fun `row with GPS formatter but null location writes empty GPS placeholders`() {
        val telemetry = TelemetryState(voltage = 23000) // 230.00 V
        val row = withGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0, gps = null)
        val cols = row.split(",")
        // Should have 24 columns (same as with GPS data) so CsvParser reads correctly
        assertEquals(24, cols.size)
        // GPS columns should be empty
        assertEquals("", cols[2])  // latitude
        assertEquals("", cols[3])  // longitude
        assertEquals("", cols[4])  // gps_speed
        assertEquals("", cols[5])  // gps_alt
        assertEquals("", cols[6])  // gps_heading
        assertEquals("", cols[7])  // gps_distance
        // Telemetry columns should be at correct positions
        assertEquals("0.00", cols[8])    // speed
        assertEquals("230.00", cols[9])  // voltage
    }

    @Test
    fun `row with default state produces all zeros`() {
        val telemetry = TelemetryState()
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0)
        val cols = row.split(",")
        assertEquals("0.00", cols[2])  // speed
        assertEquals("0.00", cols[3])  // voltage
        assertEquals("0.00", cols[4])  // phase_current
        assertEquals("0.00", cols[5])  // current
        assertEquals("0.00", cols[6])  // power
        assertEquals("0", cols[9])     // battery_level
        assertEquals("0", cols[10])    // distance
        assertEquals("0", cols[11])    // totaldistance
    }

    @Test
    fun `GPS data silently ignored when formatter created without GPS`() {
        val telemetry = TelemetryState(speed = 2500)
        val gps = GpsLocation(37.7749, -122.4194, 20.0, 50.5, 90.0, 1234.0)
        val row = noGps.row("2024-01-15,10:30:00.000", telemetry, tripDistance = 0, gps = gps)
        val cols = row.split(",")
        // Should have 18 columns (no GPS) despite gps being non-null
        assertEquals(18, cols.size)
        assertEquals("25.00", cols[2]) // speed at index 2, not latitude
    }
}
