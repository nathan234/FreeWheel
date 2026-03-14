package org.freewheel.core.utils

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunCalculatorTest {

    @Test
    fun `sunrise before sunset for typical latitude`() {
        // Summer solstice at New York (40.7N, -74.0W)
        val result = SunCalculator.calculate(dayOfYear = 172, latitudeDeg = 40.7, longitudeDeg = -74.0)
        assertNotNull(result)
        assertTrue(result.sunriseHourUtc < result.sunsetHourUtc, "Sunrise should be before sunset")
    }

    @Test
    fun `new york summer sunrise is around 9-10 UTC`() {
        // June 21 at NYC: sunrise ~9:25 UTC (5:25 EDT)
        val result = SunCalculator.calculate(dayOfYear = 172, latitudeDeg = 40.7, longitudeDeg = -74.0)
        assertNotNull(result)
        assertTrue(result.sunriseHourUtc in 9.0..10.5, "NYC summer sunrise should be ~9-10 UTC, got ${result.sunriseHourUtc}")
    }

    @Test
    fun `new york summer sunset is around 0-2 next day UTC`() {
        // June 21 at NYC: sunset ~0:30 UTC next day (8:30 PM EDT)
        val result = SunCalculator.calculate(dayOfYear = 172, latitudeDeg = 40.7, longitudeDeg = -74.0)
        assertNotNull(result)
        assertTrue(result.sunsetHourUtc in 23.0..25.5, "NYC summer sunset should be ~24 UTC, got ${result.sunsetHourUtc}")
    }

    @Test
    fun `equator has roughly 12h daylight`() {
        // Equinox at equator (0N, 0E)
        val result = SunCalculator.calculate(dayOfYear = 80, latitudeDeg = 0.0, longitudeDeg = 0.0)
        assertNotNull(result)
        val dayLength = result.sunsetHourUtc - result.sunriseHourUtc
        assertTrue(abs(dayLength - 12.0) < 0.5, "Equator equinox should be ~12h, got $dayLength")
    }

    @Test
    fun `polar region returns null in winter`() {
        // December at 80N (polar night)
        val result = SunCalculator.calculate(dayOfYear = 355, latitudeDeg = 80.0, longitudeDeg = 0.0)
        assertNull(result, "Should return null for polar night")
    }

    @Test
    fun `isDark returns true before sunrise`() {
        val sunTimes = SunTimes(sunriseHourUtc = 6.0, sunsetHourUtc = 18.0)
        assertTrue(SunCalculator.isDark(3.0, sunTimes))
    }

    @Test
    fun `isDark returns false at midday`() {
        val sunTimes = SunTimes(sunriseHourUtc = 6.0, sunsetHourUtc = 18.0)
        assertEquals(false, SunCalculator.isDark(12.0, sunTimes))
    }

    @Test
    fun `isDark returns true after sunset`() {
        val sunTimes = SunTimes(sunriseHourUtc = 6.0, sunsetHourUtc = 18.0)
        assertTrue(SunCalculator.isDark(21.0, sunTimes))
    }

    @Test
    fun `dayOfYearFromEpoch for known date`() {
        // 2024-03-14 00:00:00 UTC = day 74 of leap year 2024
        // epoch millis: 1710374400000
        val day = SunCalculator.dayOfYearFromEpoch(1710374400000L)
        assertEquals(74, day)
    }

    @Test
    fun `dayOfYearFromEpoch for Jan 1 1970`() {
        assertEquals(1, SunCalculator.dayOfYearFromEpoch(0L))
    }

    @Test
    fun `epoch millis overload matches day-of-year overload`() {
        // Use a known epoch millis for 2024-06-21 (day 173, leap year)
        val epochMillis = 1718928000000L // 2024-06-21 00:00:00 UTC
        val fromEpoch = SunCalculator.calculate(epochMillis, 40.7, -74.0)
        val fromDay = SunCalculator.calculate(173, 40.7, -74.0)
        assertNotNull(fromEpoch)
        assertNotNull(fromDay)
        assertTrue(abs(fromEpoch.sunriseHourUtc - fromDay.sunriseHourUtc) < 0.1)
    }
}
