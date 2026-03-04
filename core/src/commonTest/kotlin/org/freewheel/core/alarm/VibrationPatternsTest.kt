package org.freewheel.core.alarm

import org.freewheel.core.domain.AlarmType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class VibrationPatternsTest {

    // ==================== Pattern Mapping ====================

    @Test
    fun `SPEED1 returns speed pattern`() {
        assertContentEquals(VibrationPatterns.SPEED_PATTERN, VibrationPatterns.forAlarmType(AlarmType.SPEED1))
    }

    @Test
    fun `SPEED2 returns speed pattern`() {
        assertContentEquals(VibrationPatterns.SPEED_PATTERN, VibrationPatterns.forAlarmType(AlarmType.SPEED2))
    }

    @Test
    fun `SPEED3 returns speed pattern`() {
        assertContentEquals(VibrationPatterns.SPEED_PATTERN, VibrationPatterns.forAlarmType(AlarmType.SPEED3))
    }

    @Test
    fun `PWM returns speed pattern`() {
        assertContentEquals(VibrationPatterns.SPEED_PATTERN, VibrationPatterns.forAlarmType(AlarmType.PWM))
    }

    @Test
    fun `CURRENT returns current pattern`() {
        assertContentEquals(VibrationPatterns.CURRENT_PATTERN, VibrationPatterns.forAlarmType(AlarmType.CURRENT))
    }

    @Test
    fun `TEMPERATURE returns temperature pattern`() {
        assertContentEquals(VibrationPatterns.TEMPERATURE_PATTERN, VibrationPatterns.forAlarmType(AlarmType.TEMPERATURE))
    }

    @Test
    fun `BATTERY returns battery pattern`() {
        assertContentEquals(VibrationPatterns.BATTERY_PATTERN, VibrationPatterns.forAlarmType(AlarmType.BATTERY))
    }

    @Test
    fun `WHEEL returns wheel pattern`() {
        assertContentEquals(VibrationPatterns.WHEEL_PATTERN, VibrationPatterns.forAlarmType(AlarmType.WHEEL))
    }

    // ==================== Pattern Structure ====================

    @Test
    fun `all patterns start with zero delay`() {
        // First element should be 0 (no initial delay before first vibration)
        assertTrue(VibrationPatterns.SPEED_PATTERN[0] == 0L)
        assertTrue(VibrationPatterns.CURRENT_PATTERN[0] == 0L)
        assertTrue(VibrationPatterns.TEMPERATURE_PATTERN[0] == 0L)
        assertTrue(VibrationPatterns.BATTERY_PATTERN[0] == 0L)
        assertTrue(VibrationPatterns.WHEEL_PATTERN[0] == 0L)
    }

    @Test
    fun `all patterns have at least 3 elements`() {
        // Minimum: [delay, vibrate, pause]
        assertTrue(VibrationPatterns.SPEED_PATTERN.size >= 3)
        assertTrue(VibrationPatterns.CURRENT_PATTERN.size >= 3)
        assertTrue(VibrationPatterns.TEMPERATURE_PATTERN.size >= 3)
        assertTrue(VibrationPatterns.BATTERY_PATTERN.size >= 3)
        assertTrue(VibrationPatterns.WHEEL_PATTERN.size >= 3)
    }

    @Test
    fun `all pattern values are non-negative`() {
        val allPatterns = listOf(
            VibrationPatterns.SPEED_PATTERN,
            VibrationPatterns.CURRENT_PATTERN,
            VibrationPatterns.TEMPERATURE_PATTERN,
            VibrationPatterns.BATTERY_PATTERN,
            VibrationPatterns.WHEEL_PATTERN
        )
        for (pattern in allPatterns) {
            for (value in pattern) {
                assertTrue(value >= 0, "Pattern value should be non-negative: $value")
            }
        }
    }

    // ==================== Pattern Distinctiveness ====================

    @Test
    fun `each alarm type has distinct pattern characteristics`() {
        // Speed: short burst (100ms vibrate)
        assertTrue(VibrationPatterns.SPEED_PATTERN[1] == 100L)

        // Current: rapid pulses (50ms vibrate)
        assertTrue(VibrationPatterns.CURRENT_PATTERN[1] == 50L)

        // Temperature: long pulse (500ms vibrate)
        assertTrue(VibrationPatterns.TEMPERATURE_PATTERN[1] == 500L)

        // Battery: short vibrate (100ms) with long pause (500ms)
        assertTrue(VibrationPatterns.BATTERY_PATTERN[1] == 100L)
        assertTrue(VibrationPatterns.BATTERY_PATTERN[2] == 500L)

        // Wheel: quick pulse (50ms vibrate)
        assertTrue(VibrationPatterns.WHEEL_PATTERN[1] == 50L)
    }

    @Test
    fun `current pattern has multiple pulses`() {
        // Current alarm should have rapid repeated pulses
        assertTrue(VibrationPatterns.CURRENT_PATTERN.size >= 5,
            "Current pattern should have multiple vibration pulses")
    }

    // ==================== All AlarmTypes Covered ====================

    @Test
    fun `forAlarmType handles all AlarmType values`() {
        // This test ensures the when expression is exhaustive
        // If a new AlarmType is added without updating forAlarmType, this will fail to compile
        for (alarmType in AlarmType.entries) {
            val pattern = VibrationPatterns.forAlarmType(alarmType)
            assertTrue(pattern.isNotEmpty(), "Pattern for $alarmType should not be empty")
        }
    }
}
