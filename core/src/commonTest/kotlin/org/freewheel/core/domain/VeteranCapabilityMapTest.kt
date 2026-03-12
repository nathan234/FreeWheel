package org.freewheel.core.domain

import org.freewheel.core.protocol.VeteranDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VeteranCapabilityMapTest {

    private val map = VeteranDecoder.CAPABILITY_MAP

    @Test
    fun `level 0 resolves only base commands`() {
        val cap = map.resolveAt(firmwareLevel = 0)
        assertTrue(cap.isResolved)
        assertTrue(cap.supports(SettingsCommandId.LIGHT_MODE))
        assertTrue(cap.supports(SettingsCommandId.PEDALS_MODE))
        assertTrue(cap.supports(SettingsCommandId.LOCK))
        assertTrue(cap.supports(SettingsCommandId.RESET_TRIP))
        assertEquals(4, cap.supportedCommands.size)
    }

    @Test
    fun `level 2 still only base commands`() {
        val cap = map.resolveAt(firmwareLevel = 2)
        assertEquals(4, cap.supportedCommands.size)
        assertFalse(cap.supports(SettingsCommandId.ALARM_SPEED_1))
    }

    @Test
    fun `level 3 unlocks extended commands`() {
        val cap = map.resolveAt(firmwareLevel = 3)
        // All 19 commands available
        assertEquals(map.size, cap.supportedCommands.size)
        assertTrue(cap.supports(SettingsCommandId.ALARM_SPEED_1))
        assertTrue(cap.supports(SettingsCommandId.PEDAL_TILT))
        assertTrue(cap.supports(SettingsCommandId.HIGH_SPEED_MODE))
        assertTrue(cap.supports(SettingsCommandId.CALIBRATE))
        assertTrue(cap.supports(SettingsCommandId.POWER_OFF))
        assertTrue(cap.supports(SettingsCommandId.LATERAL_CUTOFF_ANGLE))
    }

    @Test
    fun `higher levels still resolve all commands`() {
        val cap5 = map.resolveAt(firmwareLevel = 5)
        val cap42 = map.resolveAt(firmwareLevel = 42) // Nosfet Apex
        assertEquals(map.size, cap5.supportedCommands.size)
        assertEquals(map.size, cap42.supportedCommands.size)
    }

    @Test
    fun `resolveAt carries metadata through`() {
        val cap = map.resolveAt(
            firmwareLevel = 3,
            detectedModel = "Sherman S",
            firmwareVersion = "3.50"
        )
        assertEquals("Sherman S", cap.detectedModel)
        assertEquals("3.50", cap.firmwareVersion)
        assertEquals(3, cap.firmwareLevel)
    }

    @Test
    fun `map has exactly the expected level-0 commands`() {
        val level0 = map.filterValues { it == 0 }.keys
        assertEquals(
            setOf(
                SettingsCommandId.LIGHT_MODE,
                SettingsCommandId.PEDALS_MODE,
                SettingsCommandId.LOCK,
                SettingsCommandId.RESET_TRIP
            ),
            level0
        )
    }
}
