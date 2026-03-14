package org.freewheel.core.domain.dashboard

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.telemetry.MetricType

/**
 * Registry of all metrics that can be displayed on the dashboard.
 * Each entry carries display metadata, color thresholds, widget support,
 * unit classification, and a value extractor from [WheelState].
 *
 * GPS_SPEED returns null from [extractValue] — the platform provides GPS speed separately.
 */
enum class DashboardMetric(
    val label: String,
    val unit: String,
    val maxValueSpec: MaxValueSpec,
    val decimals: Int,
    val greenBelow: Double,
    val redAbove: Double,
    val invertedColor: Boolean,
    val unitCategory: UnitCategory,
    val supportedDisplayTypes: Set<WidgetType>,
    val supportedWheelTypes: Set<WheelType>?,
    val colorHex: Long
) {
    SPEED(
        label = "Speed", unit = "km/h",
        maxValueSpec = MaxValueSpec.Fixed(50.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.SPEED,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.HERO_GAUGE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF2196F3
    ),
    GPS_SPEED(
        label = "GPS Speed", unit = "km/h",
        maxValueSpec = MaxValueSpec.Fixed(50.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.SPEED,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.HERO_GAUGE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF00BCD4
    ),
    BATTERY(
        label = "Battery", unit = "%",
        maxValueSpec = MaxValueSpec.Fixed(100.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = true,
        unitCategory = UnitCategory.PERCENTAGE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.HERO_GAUGE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF4CAF50
    ),
    VOLTAGE(
        label = "Voltage", unit = "V",
        maxValueSpec = MaxValueSpec.Fixed(100.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.VOLTAGE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF9C27B0
    ),
    CURRENT(
        label = "Current", unit = "A",
        maxValueSpec = MaxValueSpec.Dynamic(minimumDefault = 100.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.CURRENT,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFFFF9800
    ),
    PHASE_CURRENT(
        label = "Phase Current", unit = "A",
        maxValueSpec = MaxValueSpec.Dynamic(minimumDefault = 100.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.CURRENT,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(
            WheelType.GOTWAY, WheelType.GOTWAY_VIRTUAL, WheelType.VETERAN
        ),
        colorHex = 0xFFFF9800
    ),
    POWER(
        label = "Power", unit = "W",
        maxValueSpec = MaxValueSpec.Dynamic(minimumDefault = 2000.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.POWER,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.HERO_GAUGE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF4CAF50
    ),
    PWM(
        label = "PWM", unit = "%",
        maxValueSpec = MaxValueSpec.Fixed(100.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.PERCENTAGE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.HERO_GAUGE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFFE91E63
    ),
    TEMPERATURE(
        label = "Temp", unit = "\u00B0C",
        maxValueSpec = MaxValueSpec.Fixed(80.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.6875,
        invertedColor = false,
        unitCategory = UnitCategory.TEMPERATURE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFFF44336
    ),
    TEMPERATURE_2(
        label = "Temp 2", unit = "\u00B0C",
        maxValueSpec = MaxValueSpec.Fixed(80.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.6875,
        invertedColor = false,
        unitCategory = UnitCategory.TEMPERATURE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(
            WheelType.KINGSONG, WheelType.GOTWAY, WheelType.GOTWAY_VIRTUAL,
            WheelType.INMOTION, WheelType.INMOTION_V2
        ),
        colorHex = 0xFFF44336
    ),
    TRIP_DISTANCE(
        label = "Trip Distance", unit = "km",
        maxValueSpec = MaxValueSpec.None,
        decimals = 2, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.DISTANCE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF607D8B
    ),
    TOTAL_DISTANCE(
        label = "Total Distance", unit = "km",
        maxValueSpec = MaxValueSpec.None,
        decimals = 1, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.DISTANCE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = null,
        colorHex = 0xFF607D8B
    ),
    ANGLE(
        label = "Angle", unit = "\u00B0",
        maxValueSpec = MaxValueSpec.Fixed(30.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.ANGLE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(
            WheelType.VETERAN, WheelType.INMOTION, WheelType.INMOTION_V2
        ),
        colorHex = 0xFF795548
    ),
    ROLL(
        label = "Roll", unit = "\u00B0",
        maxValueSpec = MaxValueSpec.Fixed(30.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.ANGLE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION, WheelType.INMOTION_V2),
        colorHex = 0xFF795548
    ),
    TORQUE(
        label = "Torque", unit = "Nm",
        maxValueSpec = MaxValueSpec.Dynamic(minimumDefault = 100.0),
        decimals = 1, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.NONE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFF607D8B
    ),
    MOTOR_POWER(
        label = "Motor Power", unit = "W",
        maxValueSpec = MaxValueSpec.Dynamic(minimumDefault = 2000.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.POWER,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFF4CAF50
    ),
    CPU_TEMP(
        label = "CPU Temp", unit = "\u00B0C",
        maxValueSpec = MaxValueSpec.Fixed(80.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.6875,
        invertedColor = false,
        unitCategory = UnitCategory.TEMPERATURE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFFF44336
    ),
    IMU_TEMP(
        label = "IMU Temp", unit = "\u00B0C",
        maxValueSpec = MaxValueSpec.Fixed(80.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.6875,
        invertedColor = false,
        unitCategory = UnitCategory.TEMPERATURE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFFF44336
    ),
    CPU_LOAD(
        label = "CPU Load", unit = "%",
        maxValueSpec = MaxValueSpec.Fixed(100.0),
        decimals = 0, greenBelow = 0.5, redAbove = 0.75,
        invertedColor = false,
        unitCategory = UnitCategory.PERCENTAGE,
        supportedDisplayTypes = setOf(WidgetType.GAUGE_TILE, WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.KINGSONG),
        colorHex = 0xFF607D8B
    ),
    SPEED_LIMIT(
        label = "Speed Limit", unit = "km/h",
        maxValueSpec = MaxValueSpec.None,
        decimals = 1, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.SPEED,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFF607D8B
    ),
    CURRENT_LIMIT(
        label = "Current Limit", unit = "A",
        maxValueSpec = MaxValueSpec.None,
        decimals = 1, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.CURRENT,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.INMOTION_V2),
        colorHex = 0xFF607D8B
    ),
    FAN_STATUS(
        label = "Fan", unit = "",
        maxValueSpec = MaxValueSpec.Fixed(1.0),
        decimals = 0, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.NONE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.KINGSONG),
        colorHex = 0xFF607D8B
    ),
    ALERT_SPEED(
        label = "Alert Speed", unit = "km/h",
        maxValueSpec = MaxValueSpec.None,
        decimals = 0, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.SPEED,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.VETERAN),
        colorHex = 0xFFFF5722
    ),
    AUTO_OFF_TIME(
        label = "Auto Off", unit = "s",
        maxValueSpec = MaxValueSpec.None,
        decimals = 0, greenBelow = 1.0, redAbove = 1.0,
        invertedColor = false,
        unitCategory = UnitCategory.NONE,
        supportedDisplayTypes = setOf(WidgetType.STAT_ROW),
        supportedWheelTypes = setOf(WheelType.VETERAN),
        colorHex = 0xFF607D8B
    );

    /**
     * Extract this metric's raw value from [WheelState].
     * Returns null for [GPS_SPEED] — platform provides GPS speed separately.
     * Speed/distance values are in km/h or km (not converted for display units).
     */
    fun extractValue(state: WheelState): Double? = when (this) {
        GPS_SPEED -> null
        SPEED -> state.speedKmh
        BATTERY -> state.batteryLevel.toDouble()
        VOLTAGE -> state.voltageV
        CURRENT -> state.currentA
        PHASE_CURRENT -> state.phaseCurrentA
        POWER -> state.powerW
        PWM -> state.pwmPercent
        TEMPERATURE -> state.temperatureC.toDouble()
        TEMPERATURE_2 -> state.temperature2C.toDouble()
        TRIP_DISTANCE -> state.wheelDistanceKm
        TOTAL_DISTANCE -> state.totalDistanceKm
        ANGLE -> state.angle
        ROLL -> state.roll
        TORQUE -> state.torque
        MOTOR_POWER -> state.motorPower
        CPU_TEMP -> state.cpuTemp.toDouble()
        IMU_TEMP -> state.imuTemp.toDouble()
        CPU_LOAD -> state.cpuLoad.toDouble()
        SPEED_LIMIT -> state.speedLimit
        CURRENT_LIMIT -> state.currentLimit
        FAN_STATUS -> state.fanStatus.toDouble()
        ALERT_SPEED -> state.alertSpeed.toDouble()
        AUTO_OFF_TIME -> state.autoOffTime.toDouble()
    }

    /**
     * Canonical color zone determination based on progress fraction (0..1).
     * For [invertedColor] metrics (BATTERY), low values are bad (red).
     */
    fun colorZone(progress: Double): ColorZone = when {
        invertedColor -> when {
            progress > greenBelow -> ColorZone.GREEN
            progress > (1.0 - redAbove) -> ColorZone.ORANGE
            else -> ColorZone.RED
        }
        else -> when {
            progress < greenBelow -> ColorZone.GREEN
            progress < redAbove -> ColorZone.ORANGE
            else -> ColorZone.RED
        }
    }

    /**
     * Map to [MetricType] for sparkline buffer access.
     * Only the 6 existing MetricType entries have sparkline data; others return null.
     */
    val sparklineKey: MetricType? get() = when (this) {
        SPEED -> MetricType.SPEED
        GPS_SPEED -> MetricType.GPS_SPEED
        BATTERY -> MetricType.BATTERY
        POWER -> MetricType.POWER
        PWM -> MetricType.PWM
        TEMPERATURE -> MetricType.TEMPERATURE
        else -> null
    }

    @Deprecated("Use sparklineKey", ReplaceWith("sparklineKey"))
    fun toMetricType(): MetricType? = sparklineKey

    /** Whether this metric is available for the given wheel type. */
    fun isAvailableFor(wheelType: WheelType): Boolean {
        val types = supportedWheelTypes ?: return true
        return wheelType in types
    }

    /**
     * Wheel-aware maximum for gauge progress and color thresholds.
     * Uses wheel-reported limits when available, falls back to fixed maxValue or spec defaults.
     */
    fun effectiveMax(wheelState: WheelState): Double = when (this) {
        SPEED, GPS_SPEED -> wheelState.speedLimit.takeIf { it > 0 }
            ?: wheelState.maxSpeed.takeIf { it > 0 }?.toDouble()
            ?: maxValue.takeIf { it > 0 } ?: 50.0
        CURRENT -> wheelState.currentLimit.takeIf { it > 0 }
            ?: (maxValueSpec as? MaxValueSpec.Dynamic)?.minimumDefault ?: 100.0
        else -> maxValue.takeIf { it > 0.0 } ?: 100.0
    }

    /** Backward-compatible bridge: maxValue as Double (0.0 for Dynamic/None). */
    val maxValue: Double get() = maxValueSpec.fixedValueOrNull() ?: 0.0

    /** True if this metric represents a speed value (derived from [unitCategory]). */
    val isSpeedMetric: Boolean get() = unitCategory == UnitCategory.SPEED

    /** True if this metric represents a distance value (derived from [unitCategory]). */
    val isDistanceMetric: Boolean get() = unitCategory == UnitCategory.DISTANCE

    /** True if this metric represents a temperature value (derived from [unitCategory]). */
    val isTemperatureMetric: Boolean get() = unitCategory == UnitCategory.TEMPERATURE
}
