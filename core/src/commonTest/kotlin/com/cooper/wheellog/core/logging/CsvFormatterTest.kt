package com.cooper.wheellog.core.logging

import com.cooper.wheellog.core.domain.WheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CsvFormatterTest {

    // ==================== Header ====================

    @Test
    fun `header without GPS has expected columns`() {
        val h = CsvFormatter.header(includeGps = false)
        assertEquals(
            "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
                "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert",
            h
        )
    }

    @Test
    fun `header with GPS inserts six GPS columns after time`() {
        val h = CsvFormatter.header(includeGps = true)
        val cols = h.split(",")
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
        val cols = CsvFormatter.header(includeGps = true).split(",")
        // 18 base + 6 GPS = 24
        assertEquals(24, cols.size)
    }

    @Test
    fun `header without GPS has 18 columns`() {
        val cols = CsvFormatter.header(includeGps = false).split(",")
        assertEquals(18, cols.size)
    }

    // ==================== Row ====================

    @Test
    fun `row without GPS produces correct column count`() {
        val state = WheelState()
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals(18, cols.size)
    }

    @Test
    fun `row with GPS produces correct column count`() {
        val state = WheelState()
        val gps = GpsLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 25.0,
            altitude = 10.0,
            bearing = 180.0,
            cumulativeDistance = 500.0
        )
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0, gps)
        val cols = row.split(",")
        assertEquals(24, cols.size)
    }

    @Test
    fun `row dateTime appears in first two columns`() {
        val state = WheelState()
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("2024-01-15", cols[0])
        assertEquals("10:30:00.000", cols[1])
    }

    @Test
    fun `row encodes speed with 2 decimals`() {
        // speed=2500 -> 25.00 km/h
        val state = WheelState(speed = 2500)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("25.00", cols[2]) // speed column (index 2 without GPS)
    }

    @Test
    fun `row encodes voltage with 2 decimals`() {
        // voltage=8400 -> 84.00 V
        val state = WheelState(voltage = 8400)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("84.00", cols[3]) // voltage
    }

    @Test
    fun `row encodes phase current with 2 decimals`() {
        val state = WheelState(phaseCurrent = 1550) // 15.50 A
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("15.50", cols[4])
    }

    @Test
    fun `row encodes current with 2 decimals`() {
        val state = WheelState(current = 1200) // 12.00 A
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("12.00", cols[5])
    }

    @Test
    fun `row encodes power with 2 decimals`() {
        val state = WheelState(power = 150000) // 1500.00 W
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("1500.00", cols[6])
    }

    @Test
    fun `row encodes torque with 2 decimals`() {
        val state = WheelState(torque = 12.34)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("12.34", cols[7])
    }

    @Test
    fun `row encodes pwm with 2 decimals`() {
        // calculatedPwm=0.4567 -> pwmPercent = 45.67
        val state = WheelState(calculatedPwm = 0.4567)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("45.67", cols[8])
    }

    @Test
    fun `row encodes battery level as integer`() {
        val state = WheelState(batteryLevel = 85)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("85", cols[9])
    }

    @Test
    fun `row encodes trip distance as integer meters`() {
        val state = WheelState()
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 1234)
        val cols = row.split(",")
        assertEquals("1234", cols[10]) // distance
    }

    @Test
    fun `row encodes total distance as long meters`() {
        val state = WheelState(totalDistance = 999999)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("999999", cols[11]) // totaldistance
    }

    @Test
    fun `row encodes temperatures as integers`() {
        // temperature=3500 -> 35 °C, temperature2=2800 -> 28 °C
        val state = WheelState(temperature = 3500, temperature2 = 2800)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("35", cols[12])  // system_temp
        assertEquals("28", cols[13])  // temp2
    }

    @Test
    fun `row encodes tilt and roll with 2 decimals`() {
        val state = WheelState(angle = 2.5, roll = -1.3)
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("2.50", cols[14])  // tilt
        assertEquals("-1.30", cols[15]) // roll
    }

    @Test
    fun `row encodes mode and alert strings`() {
        val state = WheelState(modeStr = "Sport", alert = "")
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
        val cols = row.split(",")
        assertEquals("Sport", cols[16])
        assertEquals("", cols[17])
    }

    @Test
    fun `row with GPS includes location data`() {
        val state = WheelState(speed = 2000)
        val gps = GpsLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 20.0,
            altitude = 50.5,
            bearing = 90.0,
            cumulativeDistance = 1234.0
        )
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 100, gps)
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
        val state = WheelState()
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0, null)
        assertFalse(row.contains("37.7749"))
    }

    @Test
    fun `row with default state produces all zeros`() {
        val state = WheelState()
        val row = CsvFormatter.row("2024-01-15,10:30:00.000", state, 0)
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
}
