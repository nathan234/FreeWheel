package org.freewheel.core.utils

import org.freewheel.core.domain.WheelType
import org.freewheel.core.telemetry.MetricType

/**
 * Shared display formatting utilities for both Android and iOS.
 * Single source of truth for unit conversion display, wheel settings text,
 * signal strength, and wheel identity.
 */
object DisplayUtils {

    // --- Speed ---

    fun formatSpeed(kmh: Double, useMph: Boolean, decimals: Int = 0): String {
        val value = if (useMph) ByteUtils.kmToMiles(kmh) else kmh
        return "${StringUtil.formatDecimal(value, decimals)} ${speedUnit(useMph)}"
    }

    fun speedUnit(useMph: Boolean): String = if (useMph) "mph" else "km/h"

    // --- Distance ---

    fun formatDistance(km: Double, useMph: Boolean, decimals: Int = 2): String {
        val value = if (useMph) ByteUtils.kmToMiles(km) else km
        return "${StringUtil.formatDecimal(value, decimals)} ${distanceUnit(useMph)}"
    }

    fun distanceUnit(useMph: Boolean): String = if (useMph) "mi" else "km"

    // --- Temperature ---

    fun formatTemperature(celsius: Double, useFahrenheit: Boolean, decimals: Int = 0): String {
        val value = if (useFahrenheit) ByteUtils.celsiusToFahrenheit(celsius) else celsius
        return "${StringUtil.formatDecimal(value, decimals)}${temperatureUnit(useFahrenheit)}"
    }

    fun temperatureUnit(useFahrenheit: Boolean): String = if (useFahrenheit) "\u00B0F" else "\u00B0C"

    // --- Duration ---

    fun formatDurationShort(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    fun formatDurationCompact(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "$hours:${padZero(minutes)}:${padZero(secs)}"
        } else {
            "$minutes:${padZero(secs)}"
        }
    }

    private fun padZero(value: Int): String = if (value < 10) "0$value" else value.toString()

    // --- Simple value conversion ---

    fun maxSpeedDefault(useMph: Boolean): Double = if (useMph) 31.0 else 50.0

    fun convertSpeed(kmh: Double, useMph: Boolean): Double =
        if (useMph) ByteUtils.kmToMiles(kmh) else kmh

    fun convertTemp(celsius: Double, useFahrenheit: Boolean): Double =
        if (useFahrenheit) ByteUtils.celsiusToFahrenheit(celsius) else celsius

    // --- Metric conversion ---

    fun convertMetricValue(
        value: Double,
        metric: MetricType,
        useMph: Boolean,
        useFahrenheit: Boolean
    ): Double = when (metric) {
        MetricType.SPEED, MetricType.GPS_SPEED ->
            if (useMph) ByteUtils.kmToMiles(value) else value
        MetricType.TEMPERATURE ->
            if (useFahrenheit) ByteUtils.celsiusToFahrenheit(value) else value
        else -> value
    }

    fun metricUnit(metric: MetricType, useMph: Boolean, useFahrenheit: Boolean): String =
        when (metric) {
            MetricType.SPEED, MetricType.GPS_SPEED -> speedUnit(useMph)
            MetricType.TEMPERATURE -> temperatureUnit(useFahrenheit)
            else -> metric.unit
        }

    // --- Energy consumption ---

    fun formatEnergyConsumption(whPerKm: Double, useMph: Boolean, decimals: Int = 1): String {
        val value = if (useMph) whPerKm / ByteUtils.KM_TO_MILES_MULTIPLIER else whPerKm
        return "${StringUtil.formatDecimal(value, decimals)} ${energyConsumptionUnit(useMph)}"
    }

    fun energyConsumptionUnit(useMph: Boolean): String =
        if (useMph) "Wh/mi" else "Wh/km"

    // --- BMS formatting ---

    fun formatBmsVoltage(voltage: Double): String =
        "${StringUtil.formatDecimal(voltage, 2)} V"

    fun formatBmsCurrent(current: Double): String =
        "${StringUtil.formatDecimal(current, 2)} A"

    fun formatBmsTemperature(celsius: Double): String =
        "${StringUtil.formatDecimal(celsius, 1)}\u00B0C"

    fun formatBmsCell(voltage: Double): String =
        "${StringUtil.formatDecimal(voltage, 3)} V"

    fun formatBmsCellLabeled(voltage: Double, cellNum: Int): String =
        "${StringUtil.formatDecimal(voltage, 3)} V [$cellNum]"

    fun formatBmsCellIndexed(index: Int, voltage: Double): String =
        "#$index: ${StringUtil.formatDecimal(voltage, 3)} V"

    // --- Wheel settings text ---

    fun pedalsModeText(mode: Int): String = when (mode) {
        0 -> "Hard"
        1 -> "Medium"
        2 -> "Soft"
        else -> "Unknown"
    }

    fun lightModeText(mode: Int): String = when (mode) {
        0 -> "Off"
        1 -> "On"
        2 -> "Strobe"
        else -> "Unknown"
    }

    fun tiltBackSpeedText(speed: Int, useMph: Boolean): String {
        if (speed == 0) return "Off"
        return formatSpeed(speed.toDouble(), useMph)
    }

    // --- Wheel identity ---

    fun wheelDisplayName(wheelType: WheelType, model: String, name: String, btName: String = ""): String {
        val brand = wheelType.displayName
        val label = model.ifEmpty { name }.ifEmpty { btName }
        if (label.isEmpty()) return brand.ifEmpty { "Dashboard" }
        if (brand.isEmpty() || label.startsWith(brand, ignoreCase = true)) return label
        return "$brand $label"
    }

    // --- RSSI signal strength ---

    fun signalBars(rssi: Int): Int = when {
        rssi >= -50 -> 4
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        else -> 1
    }

    fun signalDescription(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Fair"
        else -> "Weak"
    }
}
