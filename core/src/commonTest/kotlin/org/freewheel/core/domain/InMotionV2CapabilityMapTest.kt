package org.freewheel.core.domain

import org.freewheel.core.protocol.InMotionV2Decoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMotionV2CapabilityMapTest {

    @Test
    fun `BASE_COMMANDS contains 17 commands`() {
        assertEquals(17, InMotionV2Decoder.BASE_COMMANDS.size)
    }

    @Test
    fun `V11 family gets base plus fan commands`() {
        val commands = buildMap {
            putAll(InMotionV2Decoder.BASE_COMMANDS)
            putAll(InMotionV2Decoder.V11_COMMANDS)
        }
        val cap = commands.resolveAt(firmwareLevel = 0)
        assertEquals(19, cap.supportedCommands.size)
        assertTrue(cap.supports(SettingsCommandId.FAN))
        assertTrue(cap.supports(SettingsCommandId.FAN_QUIET))
        assertFalse(cap.supports(SettingsCommandId.AUTO_HEADLIGHT))
        assertFalse(cap.supports(SettingsCommandId.SCREEN_AUTO_OFF))
    }

    @Test
    fun `V12 family gets base plus auto headlight and screen auto off`() {
        val commands = buildMap {
            putAll(InMotionV2Decoder.BASE_COMMANDS)
            putAll(InMotionV2Decoder.V12_COMMANDS)
        }
        val cap = commands.resolveAt(firmwareLevel = 0)
        assertEquals(19, cap.supportedCommands.size)
        assertTrue(cap.supports(SettingsCommandId.AUTO_HEADLIGHT))
        assertTrue(cap.supports(SettingsCommandId.SCREEN_AUTO_OFF))
        assertFalse(cap.supports(SettingsCommandId.FAN))
    }

    @Test
    fun `V13 V14 family gets base plus auto headlight only`() {
        val commands = buildMap {
            putAll(InMotionV2Decoder.BASE_COMMANDS)
            putAll(InMotionV2Decoder.V13_V14_COMMANDS)
        }
        val cap = commands.resolveAt(firmwareLevel = 0)
        assertEquals(18, cap.supportedCommands.size)
        assertTrue(cap.supports(SettingsCommandId.AUTO_HEADLIGHT))
        assertFalse(cap.supports(SettingsCommandId.SCREEN_AUTO_OFF))
        assertFalse(cap.supports(SettingsCommandId.FAN))
    }

    @Test
    fun `P6 gets base plus 5 exclusive commands`() {
        val commands = buildMap {
            putAll(InMotionV2Decoder.BASE_COMMANDS)
            putAll(InMotionV2Decoder.P6_COMMANDS)
        }
        val cap = commands.resolveAt(firmwareLevel = 0)
        assertEquals(22, cap.supportedCommands.size)
        assertTrue(cap.supports(SettingsCommandId.SAFE_SPEED_LIMIT))
        assertTrue(cap.supports(SettingsCommandId.SCREEN_AUTO_OFF))
        assertTrue(cap.supports(SettingsCommandId.LOGO_LIGHT_BRIGHTNESS))
        assertTrue(cap.supports(SettingsCommandId.TAIL_LIGHT_MODE))
        assertTrue(cap.supports(SettingsCommandId.TURN_SIGNAL_MODE))
        assertFalse(cap.supports(SettingsCommandId.FAN))
    }

    @Test
    fun `all IM2 capability maps use level 0`() {
        // All IM2 commands are gated by model, not firmware level,
        // so every entry should have level 0
        val allMaps = listOf(
            InMotionV2Decoder.BASE_COMMANDS,
            InMotionV2Decoder.V11_COMMANDS,
            InMotionV2Decoder.V12_COMMANDS,
            InMotionV2Decoder.V13_V14_COMMANDS,
            InMotionV2Decoder.P6_COMMANDS
        )
        for (map in allMaps) {
            assertTrue(map.values.all { it == 0 }, "All IM2 firmware levels should be 0")
        }
    }

    @Test
    fun `model families have no command overlap with each other`() {
        val v11Only = InMotionV2Decoder.V11_COMMANDS.keys
        val v12Only = InMotionV2Decoder.V12_COMMANDS.keys
        val v13v14Only = InMotionV2Decoder.V13_V14_COMMANDS.keys
        val p6Only = InMotionV2Decoder.P6_COMMANDS.keys

        // V11 fan commands should not appear in any other family
        assertTrue(v11Only.intersect(v12Only).isEmpty())
        assertTrue(v11Only.intersect(v13v14Only).isEmpty())
        assertTrue(v11Only.intersect(p6Only).isEmpty())
    }
}
