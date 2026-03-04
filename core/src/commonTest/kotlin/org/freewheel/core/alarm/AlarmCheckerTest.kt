package org.freewheel.core.alarm

import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.WheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmCheckerTest {

    private val checker = AlarmChecker()

    // ==================== PWM-based Alarms ====================

    @Test
    fun `no alarm when PWM below threshold`() {
        val config = AlarmConfig(
            pwmBasedAlarms = true,
            alarmFactor1 = 80,
            alarmFactor2 = 95
        )
        val state = WheelState(calculatedPwm = 0.70) // 70% PWM

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertFalse(result.hasAlarm)
        assertTrue(result.triggeredAlarms.isEmpty())
    }

    @Test
    fun `PWM alarm triggers when above factor1`() {
        val config = AlarmConfig(
            pwmBasedAlarms = true,
            alarmFactor1 = 80,
            alarmFactor2 = 95
        )
        val state = WheelState(calculatedPwm = 0.85) // 85% PWM

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(1, result.triggeredAlarms.size)
        assertEquals(AlarmType.PWM, result.triggeredAlarms[0].type)
    }

    @Test
    fun `PWM alarm tone duration scales with PWM level`() {
        val config = AlarmConfig(
            pwmBasedAlarms = true,
            alarmFactor1 = 80,
            alarmFactor2 = 95
        )

        // Just above threshold - short tone
        checker.reset()
        val lowPwmState = WheelState(calculatedPwm = 0.81)
        val lowResult = checker.check(lowPwmState, config, currentTimeMs = 1000)
        val lowTone = lowResult.triggeredAlarms[0].toneDuration

        // Near max - long tone
        checker.reset()
        val highPwmState = WheelState(calculatedPwm = 0.94)
        val highResult = checker.check(highPwmState, config, currentTimeMs = 2000)
        val highTone = highResult.triggeredAlarms[0].toneDuration

        assertTrue(highTone > lowTone, "Higher PWM should have longer tone duration")
        assertTrue(lowTone >= 20, "Tone duration should be at least 20ms")
        assertTrue(highTone <= 200, "Tone duration should be at most 200ms")
    }

    // ==================== Old-style Speed Alarms ====================

    @Test
    fun `old-style alarm triggers when speed and battery conditions met`() {
        val config = AlarmConfig(
            pwmBasedAlarms = false,
            alarm1Speed = 25,
            alarm1Battery = 50
        )
        val state = WheelState(
            speed = 2600, // 26 km/h (in 1/100 units)
            batteryLevel = 40
        )

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.SPEED1, result.triggeredAlarms[0].type)
    }

    @Test
    fun `old-style alarm does not trigger when battery above threshold`() {
        val config = AlarmConfig(
            pwmBasedAlarms = false,
            alarm1Speed = 25,
            alarm1Battery = 50
        )
        val state = WheelState(
            speed = 2600, // 26 km/h
            batteryLevel = 60 // Above threshold
        )

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertFalse(result.hasAlarm)
    }

    @Test
    fun `higher speed alarm takes precedence`() {
        val config = AlarmConfig(
            pwmBasedAlarms = false,
            alarm1Speed = 20,
            alarm1Battery = 80,
            alarm2Speed = 30,
            alarm2Battery = 80,
            alarm3Speed = 40,
            alarm3Battery = 80
        )
        val state = WheelState(
            speed = 4500, // 45 km/h - triggers all alarms
            batteryLevel = 50
        )

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.SPEED3, result.triggeredAlarms[0].type)
    }

    // ==================== Current Alarms ====================

    @Test
    fun `current alarm triggers when current exceeds threshold`() {
        val config = AlarmConfig(alarmCurrent = 30) // 30A threshold
        val state = WheelState(current = 3500) // 35A (in 1/100 units)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.CURRENT, result.triggeredAlarms[0].type)
    }

    @Test
    fun `current alarm triggers for negative current`() {
        val config = AlarmConfig(alarmCurrent = 30) // 30A threshold
        val state = WheelState(current = -3500) // -35A (regenerative braking)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.CURRENT, result.triggeredAlarms[0].type)
    }

    @Test
    fun `phase current alarm triggers when exceeded`() {
        val config = AlarmConfig(alarmPhaseCurrent = 50) // 50A phase current
        val state = WheelState(phaseCurrent = 5500) // 55A

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.CURRENT, result.triggeredAlarms[0].type)
    }

    // ==================== Temperature Alarms ====================

    @Test
    fun `temperature alarm triggers when board temp exceeds threshold`() {
        val config = AlarmConfig(alarmTemperature = 60) // 60°C
        val state = WheelState(temperature = 6500) // 65°C (in 1/100 units)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.TEMPERATURE, result.triggeredAlarms[0].type)
    }

    @Test
    fun `motor temperature alarm triggers when exceeded`() {
        val config = AlarmConfig(alarmMotorTemperature = 80) // 80°C
        val state = WheelState(temperature2 = 8500) // 85°C

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.TEMPERATURE, result.triggeredAlarms[0].type)
    }

    // ==================== Battery Alarms ====================

    @Test
    fun `battery alarm triggers when level below threshold`() {
        val config = AlarmConfig(alarmBattery = 20) // 20%
        val state = WheelState(batteryLevel = 15)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.BATTERY, result.triggeredAlarms[0].type)
    }

    @Test
    fun `battery alarm does not trigger when level above threshold`() {
        val config = AlarmConfig(alarmBattery = 20)
        val state = WheelState(batteryLevel = 25)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertFalse(result.hasAlarm)
    }

    // ==================== Wheel-reported Alarms ====================

    @Test
    fun `wheel alarm triggers when wheel reports alarm`() {
        val config = AlarmConfig(alarmWheel = true)
        val state = WheelState(wheelAlarm = true)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertTrue(result.hasAlarm)
        assertEquals(AlarmType.WHEEL, result.triggeredAlarms[0].type)
    }

    @Test
    fun `wheel alarm disabled when config is false`() {
        val config = AlarmConfig(alarmWheel = false)
        val state = WheelState(wheelAlarm = true)

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertFalse(result.hasAlarm)
    }

    // ==================== Throttling / Cooldown ====================

    @Test
    fun `current alarm throttles during cooldown period`() {
        val config = AlarmConfig(alarmCurrent = 30)
        val state = WheelState(current = 3500)

        // First check triggers alarm
        val result1 = checker.check(state, config, currentTimeMs = 1000)
        assertTrue(result1.hasAlarm)

        // Second check within cooldown should not trigger new alarm
        val result2 = checker.check(state, config, currentTimeMs = 1050)
        assertFalse(result2.triggeredAlarms.any { it.type == AlarmType.CURRENT })
    }

    @Test
    fun `alarm triggers again after cooldown expires`() {
        val config = AlarmConfig(alarmCurrent = 30)
        val state = WheelState(current = 3500)

        // First check
        checker.check(state, config, currentTimeMs = 1000)

        // After cooldown (170ms for current)
        val result = checker.check(state, config, currentTimeMs = 1200)
        assertTrue(result.triggeredAlarms.any { it.type == AlarmType.CURRENT })
    }

    // ==================== Multiple Alarms ====================

    @Test
    fun `multiple alarms can trigger simultaneously`() {
        val config = AlarmConfig(
            alarmCurrent = 30,
            alarmTemperature = 60,
            alarmBattery = 20
        )
        val state = WheelState(
            current = 3500,      // Triggers current alarm
            temperature = 6500,  // Triggers temp alarm
            batteryLevel = 15    // Triggers battery alarm
        )

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertEquals(3, result.triggeredAlarms.size)
        assertTrue(result.triggeredAlarms.any { it.type == AlarmType.CURRENT })
        assertTrue(result.triggeredAlarms.any { it.type == AlarmType.TEMPERATURE })
        assertTrue(result.triggeredAlarms.any { it.type == AlarmType.BATTERY })
    }

    // ==================== Bitmask ====================

    @Test
    fun `alarm bitmask correctly represents active alarms`() {
        val config = AlarmConfig(
            alarmCurrent = 30,
            alarmTemperature = 60
        )
        val state = WheelState(
            current = 3500,
            temperature = 6500
        )

        val result = checker.check(state, config, currentTimeMs = 1000)

        // Current = 0x02, Temperature = 0x04
        assertEquals(0x06, result.alarmBitmask)
    }

    // ==================== Pre-warning ====================

    @Test
    fun `pre-warning triggers for PWM below alarm but above warning threshold`() {
        val config = AlarmConfig(
            pwmBasedAlarms = true,
            alarmFactor1 = 80,
            warningPwm = 70,
            warningSpeedPeriod = 5 // 5 seconds between warnings
        )
        val state = WheelState(calculatedPwm = 0.75) // 75% - above warning, below alarm

        val result = checker.check(state, config, currentTimeMs = 1000)

        assertFalse(result.hasAlarm)
        assertNotNull(result.preWarning)
        assertEquals(PreWarningType.PWM, result.preWarning?.type)
    }

    @Test
    fun `pre-warning respects period between warnings`() {
        val config = AlarmConfig(
            pwmBasedAlarms = true,
            alarmFactor1 = 80,
            warningPwm = 70,
            warningSpeedPeriod = 5 // 5 seconds
        )
        val state = WheelState(calculatedPwm = 0.75)

        // First warning
        val result1 = checker.check(state, config, currentTimeMs = 1000)
        assertNotNull(result1.preWarning)

        // Too soon - no warning
        val result2 = checker.check(state, config, currentTimeMs = 3000)
        assertNull(result2.preWarning)

        // After period - warning again
        val result3 = checker.check(state, config, currentTimeMs = 7000)
        assertNotNull(result3.preWarning)
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears all throttle state`() {
        val config = AlarmConfig(alarmCurrent = 30)
        val state = WheelState(current = 3500)

        // Trigger alarm
        checker.check(state, config, currentTimeMs = 1000)

        // Reset
        checker.reset()

        // Should trigger again immediately
        val result = checker.check(state, config, currentTimeMs = 1050)
        assertTrue(result.hasAlarm)
    }
}
