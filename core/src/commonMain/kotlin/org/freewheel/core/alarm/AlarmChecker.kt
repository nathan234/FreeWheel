package org.freewheel.core.alarm

import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.WheelState
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure business logic for checking wheel alarms.
 *
 * This class contains no platform-specific code - it only performs calculations
 * based on [WheelState] and [AlarmConfig] to determine which alarms should trigger.
 *
 * Platform-specific effects (vibration, sound, notifications) should be handled
 * by the caller based on the [AlarmResult].
 *
 * This class is thread-safe.
 *
 * ## Usage
 * ```
 * val checker = AlarmChecker()
 * val result = checker.check(wheelState, config, currentTimeMs)
 *
 * if (result.hasAlarm) {
 *     for (alarm in result.triggeredAlarms) {
 *         // Handle alarm (play sound, vibrate, etc.)
 *     }
 * }
 * ```
 */
class AlarmChecker {

    private val lock = Lock()

    // Throttle state to prevent repeated alarms
    private var speedAlarmExecutingUntil: Long = 0
    private var currentAlarmExecutingUntil: Long = 0
    private var temperatureAlarmExecutingUntil: Long = 0
    private var batteryAlarmExecutingUntil: Long = 0
    private var wheelAlarmExecutingUntil: Long = 0
    private var lastPreWarningTime: Long = 0

    // Hysteresis: once a speed/PWM alarm triggers, it stays active until
    // the value drops below (threshold - hysteresis) to prevent rapid on/off
    // flickering when the value oscillates near the threshold.
    private var speedAlarmActive: Boolean = false
    private var speedAlarmThreshold: Double = 0.0
    private var pwmAlarmActive: Boolean = false
    private var pwmAlarmThreshold: Double = 0.0

    // Throttle durations in milliseconds
    companion object {
        private const val SPEED_ALARM_COOLDOWN_MS = 170L
        private const val CURRENT_ALARM_COOLDOWN_MS = 170L
        private const val TEMPERATURE_ALARM_COOLDOWN_MS = 570L
        private const val BATTERY_ALARM_COOLDOWN_MS = 970L
        private const val WHEEL_ALARM_COOLDOWN_MS = 170L

        /** Speed must drop this much below threshold before alarm clears (km/h) */
        private const val SPEED_HYSTERESIS_KMH = 1.0
        /** PWM must drop this much below threshold before alarm clears (0.0-1.0) */
        private const val PWM_HYSTERESIS = 0.02
    }

    /**
     * Check for alarms based on current wheel state and configuration.
     *
     * @param state Current wheel state
     * @param config Alarm configuration/thresholds
     * @param currentTimeMs Current time in milliseconds (for throttling)
     * @return Result containing all triggered alarms
     */
    fun check(state: WheelState, config: AlarmConfig, currentTimeMs: Long): AlarmResult = lock.withLock {
        val alarms = mutableListOf<TriggeredAlarm>()
        var preWarning: PreWarning? = null

        // Check speed/PWM alarms
        val speedResult = if (config.pwmBasedAlarms) {
            checkPwmAlarms(state, config, currentTimeMs)
        } else {
            checkOldStyleSpeedAlarms(state, config, currentTimeMs)
        }
        speedResult.alarm?.let { alarms.add(it) }
        if (speedResult.preWarning != null) {
            preWarning = speedResult.preWarning
        }

        // Check current alarm
        checkCurrentAlarm(state, config, currentTimeMs)?.let { alarms.add(it) }

        // Check temperature alarm
        checkTemperatureAlarm(state, config, currentTimeMs)?.let { alarms.add(it) }

        // Check battery alarm
        checkBatteryAlarm(state, config, currentTimeMs)?.let { alarms.add(it) }

        // Check wheel-reported alarm
        checkWheelAlarm(state, config, currentTimeMs)?.let { alarms.add(it) }

        AlarmResult(
            triggeredAlarms = alarms,
            preWarning = preWarning
        )
    }

    /**
     * Reset all alarm throttle states.
     * Call this when disconnecting from a wheel.
     */
    fun reset() = lock.withLock {
        speedAlarmExecutingUntil = 0
        currentAlarmExecutingUntil = 0
        temperatureAlarmExecutingUntil = 0
        batteryAlarmExecutingUntil = 0
        wheelAlarmExecutingUntil = 0
        lastPreWarningTime = 0
        speedAlarmActive = false
        speedAlarmThreshold = 0.0
        pwmAlarmActive = false
        pwmAlarmThreshold = 0.0
    }

    /**
     * Get current alarm bitmask (for compatibility).
     */
    fun getAlarmBitmask(currentTimeMs: Long): Int = lock.withLock {
        var mask = 0
        if (currentTimeMs < speedAlarmExecutingUntil) mask = mask or 0x01
        if (currentTimeMs < currentAlarmExecutingUntil) mask = mask or 0x02
        if (currentTimeMs < temperatureAlarmExecutingUntil) mask = mask or 0x04
        if (currentTimeMs < batteryAlarmExecutingUntil) mask = mask or 0x08
        if (currentTimeMs < wheelAlarmExecutingUntil) mask = mask or 0x10
        mask
    }

    // ==================== Private Methods ====================

    private data class SpeedCheckResult(
        val alarm: TriggeredAlarm?,
        val preWarning: PreWarning?
    )

    private fun checkPwmAlarms(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): SpeedCheckResult {
        val pwm = state.calculatedPwm  // Already 0.0-1.0

        val factor1 = config.alarmFactor1 / 100.0
        val factor2 = config.alarmFactor2 / 100.0

        // Use hysteresis: once active, alarm stays until PWM drops below (threshold - hysteresis)
        val effectiveThreshold = if (pwmAlarmActive) factor1 - PWM_HYSTERESIS else factor1

        if (pwm > effectiveThreshold) {
            // Calculate tone duration based on how far into alarm zone
            val toneDuration = ((200 * (pwm - factor1) / (factor2 - factor1)).roundToInt())
                .coerceIn(20, 200)

            speedAlarmExecutingUntil = currentTimeMs + SPEED_ALARM_COOLDOWN_MS
            pwmAlarmActive = true
            pwmAlarmThreshold = factor1

            return SpeedCheckResult(
                alarm = TriggeredAlarm(
                    type = AlarmType.PWM,
                    triggerValue = pwm * 100,  // Convert to percentage for display
                    threshold = config.alarmFactor1.toDouble(),
                    toneDuration = toneDuration
                ),
                preWarning = null
            )
        }

        // Reset speed alarm state
        speedAlarmExecutingUntil = 0
        pwmAlarmActive = false
        pwmAlarmThreshold = 0.0

        // Check for pre-warning
        val preWarning = checkPreWarning(state, config, currentTimeMs)

        return SpeedCheckResult(alarm = null, preWarning = preWarning)
    }

    private fun checkPreWarning(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): PreWarning? {
        val warningPeriodMs = config.warningSpeedPeriod * 1000L
        if (warningPeriodMs == 0L) return null

        // Check if enough time has passed since last warning
        // (Skip this check if we haven't issued any warning yet)
        if (lastPreWarningTime > 0 && currentTimeMs - lastPreWarningTime < warningPeriodMs) return null

        val warningPwm = config.warningPwm / 100.0
        val pwm = state.calculatedPwm

        if (warningPwm > 0 && pwm >= warningPwm) {
            lastPreWarningTime = currentTimeMs
            return PreWarning(PreWarningType.PWM, pwm * 100)
        }

        val warningSpeed = config.warningSpeed
        if (warningSpeed > 0 && state.speedKmh >= warningSpeed) {
            lastPreWarningTime = currentTimeMs
            return PreWarning(PreWarningType.SPEED, state.speedKmh)
        }

        return null
    }

    private fun checkOldStyleSpeedAlarms(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): SpeedCheckResult {
        val speed = state.speedKmh
        val battery = state.batteryLevel

        // Apply hysteresis: once a speed alarm is active, require speed to drop
        // further below the threshold before clearing it
        val hysteresis = if (speedAlarmActive) SPEED_HYSTERESIS_KMH else 0.0

        // Check alarm 3 (most severe) first
        if (checkSpeedBatteryThreshold(speed, battery, config.alarm3Speed, config.alarm3Battery, hysteresis)) {
            speedAlarmExecutingUntil = currentTimeMs + SPEED_ALARM_COOLDOWN_MS
            speedAlarmActive = true
            speedAlarmThreshold = config.alarm3Speed.toDouble()
            return SpeedCheckResult(
                alarm = TriggeredAlarm(
                    type = AlarmType.SPEED3,
                    triggerValue = speed,
                    threshold = config.alarm3Speed.toDouble(),
                    toneDuration = 180
                ),
                preWarning = null
            )
        }

        // Check alarm 2
        if (checkSpeedBatteryThreshold(speed, battery, config.alarm2Speed, config.alarm2Battery, hysteresis)) {
            speedAlarmExecutingUntil = currentTimeMs + SPEED_ALARM_COOLDOWN_MS
            speedAlarmActive = true
            speedAlarmThreshold = config.alarm2Speed.toDouble()
            return SpeedCheckResult(
                alarm = TriggeredAlarm(
                    type = AlarmType.SPEED2,
                    triggerValue = speed,
                    threshold = config.alarm2Speed.toDouble(),
                    toneDuration = 100
                ),
                preWarning = null
            )
        }

        // Check alarm 1
        if (checkSpeedBatteryThreshold(speed, battery, config.alarm1Speed, config.alarm1Battery, hysteresis)) {
            speedAlarmExecutingUntil = currentTimeMs + SPEED_ALARM_COOLDOWN_MS
            speedAlarmActive = true
            speedAlarmThreshold = config.alarm1Speed.toDouble()
            return SpeedCheckResult(
                alarm = TriggeredAlarm(
                    type = AlarmType.SPEED1,
                    triggerValue = speed,
                    threshold = config.alarm1Speed.toDouble(),
                    toneDuration = 50
                ),
                preWarning = null
            )
        }

        // No speed alarm
        speedAlarmExecutingUntil = 0
        speedAlarmActive = false
        speedAlarmThreshold = 0.0
        return SpeedCheckResult(alarm = null, preWarning = null)
    }

    private fun checkSpeedBatteryThreshold(
        speed: Double,
        battery: Int,
        alarmSpeed: Int,
        alarmBattery: Int,
        hysteresis: Double = 0.0
    ): Boolean {
        return alarmSpeed > 0 &&
                alarmBattery > 0 &&
                battery <= alarmBattery &&
                speed >= alarmSpeed - hysteresis
    }

    private fun checkCurrentAlarm(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): TriggeredAlarm? {
        // Still in cooldown
        if (currentTimeMs < currentAlarmExecutingUntil) return null

        val alarmCurrent = config.alarmCurrent
        val alarmPhaseCurrent = config.alarmPhaseCurrent

        // Check regular current
        if (alarmCurrent > 0 && abs(state.currentA) >= alarmCurrent) {
            currentAlarmExecutingUntil = currentTimeMs + CURRENT_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.CURRENT,
                triggerValue = state.currentA,
                threshold = alarmCurrent.toDouble(),
                toneDuration = 100
            )
        }

        // Check phase current
        if (alarmPhaseCurrent > 0 && abs(state.phaseCurrentA) >= alarmPhaseCurrent) {
            currentAlarmExecutingUntil = currentTimeMs + CURRENT_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.CURRENT,
                triggerValue = state.phaseCurrentA,
                threshold = alarmPhaseCurrent.toDouble(),
                toneDuration = 100
            )
        }

        return null
    }

    private fun checkTemperatureAlarm(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): TriggeredAlarm? {
        // Still in cooldown
        if (currentTimeMs < temperatureAlarmExecutingUntil) return null

        val alarmTemp = config.alarmTemperature
        val alarmMotorTemp = config.alarmMotorTemperature

        // Check board temperature
        if (alarmTemp > 0 && state.temperatureC >= alarmTemp) {
            temperatureAlarmExecutingUntil = currentTimeMs + TEMPERATURE_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.TEMPERATURE,
                triggerValue = state.temperatureC.toDouble(),
                threshold = alarmTemp.toDouble(),
                toneDuration = 100
            )
        }

        // Check motor temperature
        if (alarmMotorTemp > 0 && state.temperature2C >= alarmMotorTemp) {
            temperatureAlarmExecutingUntil = currentTimeMs + TEMPERATURE_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.TEMPERATURE,
                triggerValue = state.temperature2C.toDouble(),
                threshold = alarmMotorTemp.toDouble(),
                toneDuration = 100
            )
        }

        return null
    }

    private fun checkBatteryAlarm(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): TriggeredAlarm? {
        // Still in cooldown
        if (currentTimeMs < batteryAlarmExecutingUntil) return null

        val alarmBattery = config.alarmBattery

        if (alarmBattery > 0 && state.batteryLevel <= alarmBattery) {
            batteryAlarmExecutingUntil = currentTimeMs + BATTERY_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.BATTERY,
                triggerValue = state.batteryLevel.toDouble(),
                threshold = alarmBattery.toDouble(),
                toneDuration = 100
            )
        }

        return null
    }

    private fun checkWheelAlarm(
        state: WheelState,
        config: AlarmConfig,
        currentTimeMs: Long
    ): TriggeredAlarm? {
        // Still in cooldown
        if (currentTimeMs < wheelAlarmExecutingUntil) return null

        if (config.alarmWheel && state.wheelAlarm) {
            wheelAlarmExecutingUntil = currentTimeMs + WHEEL_ALARM_COOLDOWN_MS
            return TriggeredAlarm(
                type = AlarmType.WHEEL,
                triggerValue = state.calculatedPwm * 100,
                threshold = 0.0,  // No threshold, wheel reports alarm
                toneDuration = 100
            )
        }

        return null
    }
}
