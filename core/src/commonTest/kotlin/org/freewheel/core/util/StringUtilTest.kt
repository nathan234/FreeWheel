package org.freewheel.core.util

import org.freewheel.core.utils.StringUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringUtilTest {

    // ==================== inArray ====================

    @Test
    fun `inArray returns true when value is in array`() {
        val array = arrayOf("apple", "banana", "cherry")
        assertTrue(StringUtil.inArray("banana", array))
    }

    @Test
    fun `inArray returns false when value is not in array`() {
        val array = arrayOf("apple", "banana", "cherry")
        assertFalse(StringUtil.inArray("grape", array))
    }

    @Test
    fun `inArray returns false for empty array`() {
        val array = arrayOf<String>()
        assertFalse(StringUtil.inArray("anything", array))
    }

    @Test
    fun `inArray is case sensitive`() {
        val array = arrayOf("Apple", "Banana")
        assertFalse(StringUtil.inArray("apple", array))
        assertTrue(StringUtil.inArray("Apple", array))
    }

    // ==================== startsWithAny ====================

    @Test
    fun `startsWithAny returns true when string starts with prefix`() {
        val prefixes = arrayOf("GW", "KS", "IM")
        assertTrue(StringUtil.startsWithAny("GW-Begode", prefixes))
        assertTrue(StringUtil.startsWithAny("KS-18L", prefixes))
    }

    @Test
    fun `startsWithAny returns false when no prefix matches`() {
        val prefixes = arrayOf("GW", "KS", "IM")
        assertFalse(StringUtil.startsWithAny("Ninebot", prefixes))
    }

    @Test
    fun `startsWithAny returns false for empty prefixes`() {
        val prefixes = arrayOf<String>()
        assertFalse(StringUtil.startsWithAny("anything", prefixes))
    }

    @Test
    fun `startsWithAny is case sensitive`() {
        val prefixes = arrayOf("GW", "KS")
        assertFalse(StringUtil.startsWithAny("gw-wheel", prefixes))
    }

    // ==================== formatDuration ====================

    @Test
    fun `formatDuration formats zero seconds`() {
        assertEquals("00:00:00", StringUtil.formatDuration(0))
    }

    @Test
    fun `formatDuration formats seconds only`() {
        assertEquals("00:00:05", StringUtil.formatDuration(5))
        assertEquals("00:00:59", StringUtil.formatDuration(59))
    }

    @Test
    fun `formatDuration formats minutes and seconds`() {
        assertEquals("00:01:00", StringUtil.formatDuration(60))
        assertEquals("00:01:30", StringUtil.formatDuration(90))
        assertEquals("00:59:59", StringUtil.formatDuration(3599))
    }

    @Test
    fun `formatDuration formats hours minutes and seconds`() {
        assertEquals("01:00:00", StringUtil.formatDuration(3600))
        assertEquals("01:30:45", StringUtil.formatDuration(5445))
        assertEquals("10:00:00", StringUtil.formatDuration(36000))
        assertEquals("99:59:59", StringUtil.formatDuration(359999))
    }

    @Test
    fun `formatDuration handles large values`() {
        assertEquals("100:00:00", StringUtil.formatDuration(360000))
    }

    // ==================== formatDistance ====================

    @Test
    fun `formatDistance formats zero meters`() {
        assertEquals("0.00", StringUtil.formatDistance(0))
    }

    @Test
    fun `formatDistance formats meters to km`() {
        assertEquals("1.00", StringUtil.formatDistance(1000))
        assertEquals("10.50", StringUtil.formatDistance(10500))
        assertEquals("0.10", StringUtil.formatDistance(100))
    }

    @Test
    fun `formatDistance respects decimal places`() {
        assertEquals("1.5", StringUtil.formatDistance(1500, 1))
        assertEquals("1.500", StringUtil.formatDistance(1500, 3))
        assertEquals("2", StringUtil.formatDistance(1500, 0))  // Rounds to 2
    }

    @Test
    fun `formatDistance handles large distances`() {
        assertEquals("1000.00", StringUtil.formatDistance(1000000))
    }

    // ==================== formatSpeed ====================

    @Test
    fun `formatSpeed formats zero speed`() {
        assertEquals("0.0 km/h", StringUtil.formatSpeed(0))
    }

    @Test
    fun `formatSpeed formats speed in kmh`() {
        assertEquals("25.0 km/h", StringUtil.formatSpeed(2500))
        assertEquals("10.5 km/h", StringUtil.formatSpeed(1050))
    }

    @Test
    fun `formatSpeed formats speed in mph`() {
        assertEquals("15.5 mph", StringUtil.formatSpeed(2500, useMph = true))
    }

    @Test
    fun `formatSpeed handles fractional speeds`() {
        assertEquals("0.5 km/h", StringUtil.formatSpeed(50))
    }

    // ==================== formatTemperature ====================

    @Test
    fun `formatTemperature formats celsius`() {
        assertEquals("25°C", StringUtil.formatTemperature(2500))
        assertEquals("0°C", StringUtil.formatTemperature(0))
        assertEquals("-10°C", StringUtil.formatTemperature(-1000))
    }

    @Test
    fun `formatTemperature formats fahrenheit`() {
        assertEquals("77°F", StringUtil.formatTemperature(2500, useFahrenheit = true))  // 25°C = 77°F
        assertEquals("32°F", StringUtil.formatTemperature(0, useFahrenheit = true))      // 0°C = 32°F
    }

    @Test
    fun `formatTemperature handles negative celsius to fahrenheit`() {
        // -40°C = -40°F (same in both scales)
        assertEquals("-40°F", StringUtil.formatTemperature(-4000, useFahrenheit = true))
    }

    // ==================== formatVoltage ====================

    @Test
    fun `formatVoltage formats correctly`() {
        assertEquals("0.00V", StringUtil.formatVoltage(0))
        assertEquals("84.00V", StringUtil.formatVoltage(8400))
        assertEquals("67.25V", StringUtil.formatVoltage(6725))
    }

    @Test
    fun `formatVoltage handles high voltages`() {
        assertEquals("126.50V", StringUtil.formatVoltage(12650))
    }

    // ==================== formatCurrent ====================

    @Test
    fun `formatCurrent formats correctly`() {
        assertEquals("0.00A", StringUtil.formatCurrent(0))
        assertEquals("15.00A", StringUtil.formatCurrent(1500))
        assertEquals("5.25A", StringUtil.formatCurrent(525))
    }

    @Test
    fun `formatCurrent handles negative current`() {
        assertEquals("-10.00A", StringUtil.formatCurrent(-1000))
    }

    // ==================== formatPower ====================

    @Test
    fun `formatPower formats watts`() {
        assertEquals("0.0W", StringUtil.formatPower(0))
        assertEquals("500.0W", StringUtil.formatPower(50000))
        assertEquals("999.0W", StringUtil.formatPower(99900))
    }

    @Test
    fun `formatPower switches to kW at 1000W`() {
        assertEquals("1.00kW", StringUtil.formatPower(100000))  // 1000W = 1kW
        assertEquals("2.50kW", StringUtil.formatPower(250000))  // 2500W = 2.5kW
    }

    @Test
    fun `formatPower handles high power`() {
        assertEquals("10.00kW", StringUtil.formatPower(1000000))
    }

    // ==================== formatBattery ====================

    @Test
    fun `formatBattery formats correctly`() {
        assertEquals("0%", StringUtil.formatBattery(0))
        assertEquals("50%", StringUtil.formatBattery(50))
        assertEquals("100%", StringUtil.formatBattery(100))
    }

    @Test
    fun `formatBattery handles over 100 percent`() {
        assertEquals("105%", StringUtil.formatBattery(105))
    }

    // ==================== formatDecimal ====================

    @Test
    fun `formatDecimal with 0 decimals`() {
        assertEquals("3", StringUtil.formatDecimal(3.0, 0))
        assertEquals("4", StringUtil.formatDecimal(3.6, 0))
        assertEquals("3", StringUtil.formatDecimal(3.4, 0))
    }

    @Test
    fun `formatDecimal with 1 decimal`() {
        assertEquals("3.0", StringUtil.formatDecimal(3.0, 1))
        assertEquals("3.1", StringUtil.formatDecimal(3.14, 1))
        assertEquals("3.2", StringUtil.formatDecimal(3.15, 1))
    }

    @Test
    fun `formatDecimal with 2 decimals`() {
        assertEquals("3.00", StringUtil.formatDecimal(3.0, 2))
        assertEquals("3.14", StringUtil.formatDecimal(3.14, 2))
        assertEquals("3.14", StringUtil.formatDecimal(3.141, 2))
    }

    @Test
    fun `formatDecimal with 3 decimals`() {
        assertEquals("3.000", StringUtil.formatDecimal(3.0, 3))
        assertEquals("3.142", StringUtil.formatDecimal(3.1416, 3))
    }

    @Test
    fun `formatDecimal handles negative values`() {
        assertEquals("-3.14", StringUtil.formatDecimal(-3.14, 2))
        assertEquals("-0.50", StringUtil.formatDecimal(-0.5, 2))
    }

    @Test
    fun `formatDecimal handles zero`() {
        assertEquals("0.00", StringUtil.formatDecimal(0.0, 2))
    }

    @Test
    fun `formatDecimal handles large values`() {
        assertEquals("12345.68", StringUtil.formatDecimal(12345.678, 2))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `formatDuration with negative input returns unexpected format`() {
        // Negative seconds results in unexpected behavior due to padZero
        // This documents current behavior - negative values are not properly supported
        val result = StringUtil.formatDuration(-1)
        // -1 % 60 = -1, padZero(-1) returns "0-1" because -1 < 10
        assertEquals("00:00:0-1", result)
    }

    @Test
    fun `empty string checks`() {
        assertFalse(StringUtil.inArray("", arrayOf("a", "b")))
        assertTrue(StringUtil.inArray("", arrayOf("a", "", "b")))
        assertFalse(StringUtil.startsWithAny("", arrayOf("a", "b")))
    }
}
