package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Safety-net tests for AlarmType enum.
 * Verifies all values, displayNames, fromValue round-trips,
 * and guards against enum count drift (catches KMP changes that need Swift sync).
 */
class AlarmTypeTest {

    // ==================== Enum Count Guard ====================

    @Test
    fun `AlarmType has exactly 8 entries`() {
        // If this fails, a new AlarmType was added in KMP and
        // AlarmManager.swift must be updated to handle it.
        assertEquals(8, AlarmType.entries.size)
    }

    // ==================== Value Mapping ====================

    @Test
    fun `SPEED1 has value 1`() {
        assertEquals(1, AlarmType.SPEED1.value)
    }

    @Test
    fun `SPEED2 has value 2`() {
        assertEquals(2, AlarmType.SPEED2.value)
    }

    @Test
    fun `SPEED3 has value 3`() {
        assertEquals(3, AlarmType.SPEED3.value)
    }

    @Test
    fun `CURRENT has value 4`() {
        assertEquals(4, AlarmType.CURRENT.value)
    }

    @Test
    fun `TEMPERATURE has value 5`() {
        assertEquals(5, AlarmType.TEMPERATURE.value)
    }

    @Test
    fun `PWM has value 6`() {
        assertEquals(6, AlarmType.PWM.value)
    }

    @Test
    fun `BATTERY has value 7`() {
        assertEquals(7, AlarmType.BATTERY.value)
    }

    @Test
    fun `WHEEL has value 8`() {
        assertEquals(8, AlarmType.WHEEL.value)
    }

    // ==================== Display Names ====================

    @Test
    fun `SPEED1 displayName`() {
        assertEquals("Speed 1", AlarmType.SPEED1.displayName)
    }

    @Test
    fun `SPEED2 displayName`() {
        assertEquals("Speed 2", AlarmType.SPEED2.displayName)
    }

    @Test
    fun `SPEED3 displayName`() {
        assertEquals("Speed 3", AlarmType.SPEED3.displayName)
    }

    @Test
    fun `CURRENT displayName`() {
        assertEquals("Current", AlarmType.CURRENT.displayName)
    }

    @Test
    fun `TEMPERATURE displayName`() {
        assertEquals("Temp", AlarmType.TEMPERATURE.displayName)
    }

    @Test
    fun `PWM displayName`() {
        assertEquals("PWM", AlarmType.PWM.displayName)
    }

    @Test
    fun `BATTERY displayName`() {
        assertEquals("Battery", AlarmType.BATTERY.displayName)
    }

    @Test
    fun `WHEEL displayName`() {
        assertEquals("Wheel", AlarmType.WHEEL.displayName)
    }

    // ==================== fromValue Round-Trips ====================

    @Test
    fun `fromValue round-trips all entries`() {
        for (type in AlarmType.entries) {
            assertEquals(type, AlarmType.fromValue(type.value))
        }
    }

    @Test
    fun `fromValue returns null for unknown value`() {
        assertNull(AlarmType.fromValue(0))
        assertNull(AlarmType.fromValue(99))
        assertNull(AlarmType.fromValue(-1))
    }
}
