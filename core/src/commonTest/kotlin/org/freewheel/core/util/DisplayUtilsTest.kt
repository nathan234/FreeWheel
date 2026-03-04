package org.freewheel.core.util

import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.currentTimeMillis
import org.freewheel.core.telemetry.MetricType
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.core.utils.PlatformDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayUtilsTest {

    // ==================== Speed Formatting ====================

    @Test
    fun `formatSpeed metric returns km per h`() {
        assertEquals("25 km/h", DisplayUtils.formatSpeed(25.0, useMph = false))
    }

    @Test
    fun `formatSpeed imperial converts to mph`() {
        assertEquals("16 mph", DisplayUtils.formatSpeed(25.0, useMph = true))
    }

    @Test
    fun `formatSpeed with decimals`() {
        assertEquals("25.5 km/h", DisplayUtils.formatSpeed(25.5, useMph = false, decimals = 1))
    }

    @Test
    fun `speedUnit returns correct unit`() {
        assertEquals("mph", DisplayUtils.speedUnit(true))
        assertEquals("km/h", DisplayUtils.speedUnit(false))
    }

    // ==================== Distance Formatting ====================

    @Test
    fun `formatDistance metric returns km`() {
        assertEquals("1.50 km", DisplayUtils.formatDistance(1.5, useMph = false))
    }

    @Test
    fun `formatDistance imperial converts to miles`() {
        assertEquals("0.93 mi", DisplayUtils.formatDistance(1.5, useMph = true))
    }

    @Test
    fun `formatDistance with 1 decimal`() {
        assertEquals("100.0 km", DisplayUtils.formatDistance(100.0, useMph = false, decimals = 1))
    }

    @Test
    fun `distanceUnit returns correct unit`() {
        assertEquals("mi", DisplayUtils.distanceUnit(true))
        assertEquals("km", DisplayUtils.distanceUnit(false))
    }

    // ==================== Temperature Formatting ====================

    @Test
    fun `formatTemperature metric returns celsius`() {
        assertEquals("35\u00B0C", DisplayUtils.formatTemperature(35.0, useFahrenheit = false))
    }

    @Test
    fun `formatTemperature imperial converts to fahrenheit`() {
        assertEquals("95\u00B0F", DisplayUtils.formatTemperature(35.0, useFahrenheit = true))
    }

    @Test
    fun `formatTemperature freezing point`() {
        assertEquals("32\u00B0F", DisplayUtils.formatTemperature(0.0, useFahrenheit = true))
    }

    @Test
    fun `temperatureUnit returns correct unit`() {
        assertEquals("\u00B0F", DisplayUtils.temperatureUnit(true))
        assertEquals("\u00B0C", DisplayUtils.temperatureUnit(false))
    }

    // ==================== Duration Formatting ====================

    @Test
    fun `formatDurationShort hours and minutes`() {
        assertEquals("2h 10m", DisplayUtils.formatDurationShort(130))
    }

    @Test
    fun `formatDurationShort minutes only`() {
        assertEquals("45m", DisplayUtils.formatDurationShort(45))
    }

    @Test
    fun `formatDurationShort zero`() {
        assertEquals("0m", DisplayUtils.formatDurationShort(0))
    }

    // ==================== Wheel Settings Text ====================

    @Test
    fun `pedalsModeText maps modes correctly`() {
        assertEquals("Hard", DisplayUtils.pedalsModeText(0))
        assertEquals("Medium", DisplayUtils.pedalsModeText(1))
        assertEquals("Soft", DisplayUtils.pedalsModeText(2))
        assertEquals("Unknown", DisplayUtils.pedalsModeText(99))
    }

    @Test
    fun `lightModeText maps modes correctly`() {
        assertEquals("Off", DisplayUtils.lightModeText(0))
        assertEquals("On", DisplayUtils.lightModeText(1))
        assertEquals("Strobe", DisplayUtils.lightModeText(2))
        assertEquals("Unknown", DisplayUtils.lightModeText(99))
    }

    @Test
    fun `tiltBackSpeedText off when zero`() {
        assertEquals("Off", DisplayUtils.tiltBackSpeedText(0, useMph = false))
    }

    @Test
    fun `tiltBackSpeedText metric`() {
        assertEquals("35 km/h", DisplayUtils.tiltBackSpeedText(35, useMph = false))
    }

    @Test
    fun `tiltBackSpeedText imperial`() {
        assertEquals("22 mph", DisplayUtils.tiltBackSpeedText(35, useMph = true))
    }

    // ==================== Wheel Display Name ====================

    @Test
    fun `wheelDisplayName brand plus model`() {
        assertEquals("KingSong KS-S18", DisplayUtils.wheelDisplayName(WheelType.KINGSONG, "KS-S18", ""))
    }

    @Test
    fun `wheelDisplayName brand dedup`() {
        assertEquals(
            "KingSong KS-16X",
            DisplayUtils.wheelDisplayName(WheelType.KINGSONG, "KingSong KS-16X", "")
        )
    }

    @Test
    fun `wheelDisplayName fallback to Dashboard`() {
        assertEquals("Dashboard", DisplayUtils.wheelDisplayName(WheelType.Unknown, "", ""))
    }

    @Test
    fun `wheelDisplayName brand only`() {
        assertEquals("Veteran", DisplayUtils.wheelDisplayName(WheelType.VETERAN, "", ""))
    }

    @Test
    fun `wheelDisplayName name fallback when model empty`() {
        assertEquals("MyWheel", DisplayUtils.wheelDisplayName(WheelType.Unknown, "", "MyWheel"))
    }

    @Test
    fun `wheelDisplayName brand plus name when model empty`() {
        assertEquals(
            "Begode Monster",
            DisplayUtils.wheelDisplayName(WheelType.GOTWAY, "", "Monster")
        )
    }

    @Test
    fun `wheelDisplayName falls back to btName`() {
        assertEquals(
            "Begode GW-12345",
            DisplayUtils.wheelDisplayName(WheelType.GOTWAY, "", "", "GW-12345")
        )
    }

    @Test
    fun `wheelDisplayName prefers model over btName`() {
        assertEquals(
            "Begode MCM5",
            DisplayUtils.wheelDisplayName(WheelType.GOTWAY, "MCM5", "", "GW-12345")
        )
    }

    // ==================== RSSI ====================

    @Test
    fun `signalBars returns correct bar count`() {
        assertEquals(4, DisplayUtils.signalBars(-45))
        assertEquals(3, DisplayUtils.signalBars(-55))
        assertEquals(2, DisplayUtils.signalBars(-65))
        assertEquals(1, DisplayUtils.signalBars(-80))
    }

    @Test
    fun `signalBars boundary values`() {
        assertEquals(4, DisplayUtils.signalBars(-50))
        assertEquals(3, DisplayUtils.signalBars(-60))
        assertEquals(2, DisplayUtils.signalBars(-70))
        assertEquals(1, DisplayUtils.signalBars(-71))
    }

    @Test
    fun `signalDescription returns correct labels`() {
        assertEquals("Excellent", DisplayUtils.signalDescription(-45))
        assertEquals("Good", DisplayUtils.signalDescription(-55))
        assertEquals("Fair", DisplayUtils.signalDescription(-65))
        assertEquals("Weak", DisplayUtils.signalDescription(-80))
    }

    // ==================== WheelState.displayName ====================

    @Test
    fun `WheelState displayName with model`() {
        val state = WheelState(wheelType = WheelType.KINGSONG, model = "KS-S18")
        assertEquals("KingSong KS-S18", state.displayName)
    }

    @Test
    fun `WheelState displayName brand dedup`() {
        val state = WheelState(wheelType = WheelType.KINGSONG, model = "KingSong KS-16X")
        assertEquals("KingSong KS-16X", state.displayName)
    }

    @Test
    fun `WheelState displayName fallback to Dashboard`() {
        val state = WheelState()
        assertEquals("Dashboard", state.displayName)
    }

    @Test
    fun `WheelState displayName brand only`() {
        val state = WheelState(wheelType = WheelType.VETERAN)
        assertEquals("Veteran", state.displayName)
    }

    @Test
    fun `WheelState displayName falls back to btName`() {
        val state = WheelState(wheelType = WheelType.GOTWAY, btName = "GW-12345")
        assertEquals("Begode GW-12345", state.displayName)
    }

    @Test
    fun `WheelState displayName prefers model over btName`() {
        val state = WheelState(wheelType = WheelType.GOTWAY, model = "MCM5", btName = "GW-12345")
        assertEquals("Begode MCM5", state.displayName)
    }

    @Test
    fun `WheelState displayName brand only when btName also empty`() {
        val state = WheelState(wheelType = WheelType.GOTWAY)
        assertEquals("Begode", state.displayName)
    }

    // ==================== formatDurationCompact ====================

    @Test
    fun `formatDurationCompact hours minutes seconds`() {
        assertEquals("1:01:01", DisplayUtils.formatDurationCompact(3661))
    }

    @Test
    fun `formatDurationCompact minutes and seconds only`() {
        assertEquals("1:30", DisplayUtils.formatDurationCompact(90))
    }

    @Test
    fun `formatDurationCompact zero`() {
        assertEquals("0:00", DisplayUtils.formatDurationCompact(0))
    }

    @Test
    fun `formatDurationCompact exact hour`() {
        assertEquals("1:00:00", DisplayUtils.formatDurationCompact(3600))
    }

    @Test
    fun `formatDurationCompact seconds only`() {
        assertEquals("0:05", DisplayUtils.formatDurationCompact(5))
    }

    // ==================== convertSpeed / convertTemp ====================

    @Test
    fun `convertSpeed metric returns same value`() {
        assertEquals(25.0, DisplayUtils.convertSpeed(25.0, useMph = false))
    }

    @Test
    fun `convertSpeed imperial converts to miles`() {
        assertEquals(15.5, DisplayUtils.convertSpeed(25.0, useMph = true), 0.1)
    }

    @Test
    fun `convertTemp metric returns same value`() {
        assertEquals(35.0, DisplayUtils.convertTemp(35.0, useFahrenheit = false))
    }

    @Test
    fun `convertTemp imperial converts to fahrenheit`() {
        assertEquals(95.0, DisplayUtils.convertTemp(35.0, useFahrenheit = true))
    }

    // ==================== convertMetricValue ====================

    @Test
    fun `convertMetricValue speed in mph`() {
        val result = DisplayUtils.convertMetricValue(25.0, MetricType.SPEED, useMph = true, useFahrenheit = false)
        assertEquals(15.5, result, 0.1)
    }

    @Test
    fun `convertMetricValue speed in kmh passthrough`() {
        assertEquals(25.0, DisplayUtils.convertMetricValue(25.0, MetricType.SPEED, useMph = false, useFahrenheit = false))
    }

    @Test
    fun `convertMetricValue temperature in fahrenheit`() {
        assertEquals(95.0, DisplayUtils.convertMetricValue(35.0, MetricType.TEMPERATURE, useMph = false, useFahrenheit = true))
    }

    @Test
    fun `convertMetricValue temperature in celsius passthrough`() {
        assertEquals(35.0, DisplayUtils.convertMetricValue(35.0, MetricType.TEMPERATURE, useMph = false, useFahrenheit = false))
    }

    @Test
    fun `convertMetricValue battery unchanged`() {
        assertEquals(80.0, DisplayUtils.convertMetricValue(80.0, MetricType.BATTERY, useMph = true, useFahrenheit = true))
    }

    @Test
    fun `convertMetricValue gps speed in mph`() {
        val result = DisplayUtils.convertMetricValue(25.0, MetricType.GPS_SPEED, useMph = true, useFahrenheit = false)
        assertEquals(15.5, result, 0.1)
    }

    // ==================== metricUnit ====================

    @Test
    fun `metricUnit speed metric`() {
        assertEquals("km/h", DisplayUtils.metricUnit(MetricType.SPEED, useMph = false, useFahrenheit = false))
    }

    @Test
    fun `metricUnit speed imperial`() {
        assertEquals("mph", DisplayUtils.metricUnit(MetricType.SPEED, useMph = true, useFahrenheit = false))
    }

    @Test
    fun `metricUnit temperature celsius`() {
        assertEquals("\u00B0C", DisplayUtils.metricUnit(MetricType.TEMPERATURE, useMph = false, useFahrenheit = false))
    }

    @Test
    fun `metricUnit temperature fahrenheit`() {
        assertEquals("\u00B0F", DisplayUtils.metricUnit(MetricType.TEMPERATURE, useMph = false, useFahrenheit = true))
    }

    @Test
    fun `metricUnit battery uses default`() {
        assertEquals("%", DisplayUtils.metricUnit(MetricType.BATTERY, useMph = true, useFahrenheit = true))
    }

    @Test
    fun `metricUnit power uses default`() {
        assertEquals("W", DisplayUtils.metricUnit(MetricType.POWER, useMph = false, useFahrenheit = false))
    }

    // ==================== Energy Consumption ====================

    @Test
    fun `formatEnergyConsumption metric`() {
        assertEquals("12.3 Wh/km", DisplayUtils.formatEnergyConsumption(12.3, useMph = false))
    }

    @Test
    fun `formatEnergyConsumption imperial`() {
        // 12.3 Wh/km / 0.62137 = ~19.8 Wh/mi
        assertEquals("19.8 Wh/mi", DisplayUtils.formatEnergyConsumption(12.3, useMph = true))
    }

    @Test
    fun `energyConsumptionUnit metric`() {
        assertEquals("Wh/km", DisplayUtils.energyConsumptionUnit(false))
    }

    @Test
    fun `energyConsumptionUnit imperial`() {
        assertEquals("Wh/mi", DisplayUtils.energyConsumptionUnit(true))
    }

    // ==================== MetricType.formatValue ====================

    @Test
    fun `MetricType SPEED formatValue 1 decimal`() {
        assertEquals("25.5", MetricType.SPEED.formatValue(25.5))
    }

    @Test
    fun `MetricType BATTERY formatValue 0 decimals`() {
        assertEquals("80", MetricType.BATTERY.formatValue(80.0))
    }

    @Test
    fun `MetricType POWER formatValue 0 decimals`() {
        assertEquals("1500", MetricType.POWER.formatValue(1500.0))
    }

    @Test
    fun `MetricType PWM formatValue 1 decimal`() {
        assertEquals("45.3", MetricType.PWM.formatValue(45.3))
    }

    @Test
    fun `MetricType TEMPERATURE formatValue 0 decimals`() {
        assertEquals("35", MetricType.TEMPERATURE.formatValue(35.0))
    }

    // ==================== BMS Formatting ====================

    @Test
    fun `formatBmsVoltage formats to 2 decimals`() {
        assertEquals("67.89 V", DisplayUtils.formatBmsVoltage(67.891))
    }

    @Test
    fun `formatBmsCurrent formats to 2 decimals`() {
        assertEquals("12.34 A", DisplayUtils.formatBmsCurrent(12.341))
    }

    @Test
    fun `formatBmsTemperature formats to 1 decimal with degree C`() {
        assertEquals("25.5\u00B0C", DisplayUtils.formatBmsTemperature(25.5))
    }

    @Test
    fun `formatBmsCell formats to 3 decimals`() {
        assertEquals("3.456 V", DisplayUtils.formatBmsCell(3.456))
    }

    @Test
    fun `formatBmsCellLabeled includes cell number in brackets`() {
        assertEquals("3.456 V [5]", DisplayUtils.formatBmsCellLabeled(3.456, 5))
    }

    @Test
    fun `formatBmsCellIndexed includes hash index prefix`() {
        assertEquals("#3: 3.456 V", DisplayUtils.formatBmsCellIndexed(3, 3.456))
    }

    // ==================== PlatformDateFormatter ====================

    @Test
    fun `formatFriendlyDate today shows Today prefix`() {
        // Pass the current time — it is always "today" regardless of timezone
        val now = currentTimeMillis()
        val result = PlatformDateFormatter.formatFriendlyDate(now)
        assertTrue(result.startsWith("Today,"), "Expected 'Today,' prefix but got: $result")
    }

    @Test
    fun `formatFriendlyDate yesterday shows Yesterday prefix`() {
        // Try both 24h and 36h ago — exactly one must be "yesterday" in any timezone.
        // At midnight local, 24h ago is yesterday; at 23:59 local, 36h ago is yesterday.
        val now = currentTimeMillis()
        val result24h = PlatformDateFormatter.formatFriendlyDate(now - 24 * 3_600_000L)
        val result36h = PlatformDateFormatter.formatFriendlyDate(now - 36 * 3_600_000L)

        val found = result24h.startsWith("Yesterday,") || result36h.startsWith("Yesterday,")
        assertTrue(found,
            "Expected 'Yesterday,' prefix in one of: '$result24h' / '$result36h'")
    }

    @Test
    fun `formatFriendlyDate old date shows year`() {
        // Jan 15, 2020, 12:00:00 UTC
        val oldDate = 1579089600000L
        val result = PlatformDateFormatter.formatFriendlyDate(oldDate)
        assertTrue(result.contains("2020"), "Expected year 2020 in: $result")
    }

    @Test
    fun `formatFriendlyDate zero returns Unknown date`() {
        assertEquals("Unknown date", PlatformDateFormatter.formatFriendlyDate(0))
    }

    @Test
    fun `formatFriendlyDate negative returns Unknown date`() {
        assertEquals("Unknown date", PlatformDateFormatter.formatFriendlyDate(-1))
    }

    // ==================== maxSpeedDefault ====================

    @Test
    fun `maxSpeedDefault metric returns 50`() {
        assertEquals(50.0, DisplayUtils.maxSpeedDefault(useMph = false))
    }

    @Test
    fun `maxSpeedDefault imperial returns 31`() {
        assertEquals(31.0, DisplayUtils.maxSpeedDefault(useMph = true))
    }

    // ==================== AlarmType.displayName ====================

    @Test
    fun `AlarmType displayName maps all values`() {
        assertEquals("Speed 1", AlarmType.SPEED1.displayName)
        assertEquals("Speed 2", AlarmType.SPEED2.displayName)
        assertEquals("Speed 3", AlarmType.SPEED3.displayName)
        assertEquals("Current", AlarmType.CURRENT.displayName)
        assertEquals("Temp", AlarmType.TEMPERATURE.displayName)
        assertEquals("PWM", AlarmType.PWM.displayName)
        assertEquals("Battery", AlarmType.BATTERY.displayName)
        assertEquals("Wheel", AlarmType.WHEEL.displayName)
    }
}
