package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lock the [SettingsCommandId.isAvailable] capability predicate.
 *
 * Mirrors the official Leaperkim app's `ControlActivity.initControlData` rule:
 * subtype 8 `pedalHardness == 0x80` (unsupported) hides the continuous slider
 * and shows the 3-step ride mode; any valid byte does the inverse.
 *
 * In FreeWheel terms, `pedalSensitivity >= 0` after subtype 8 parsing means
 * the wheel reports support for continuous hardness.
 */
class SettingsCommandIdAvailabilityTest {

    @Test
    fun `default Veteran state with no readback shows 3-step PedalsMode and hides continuous Pedal Hardness`() {
        val state: WheelSettings = WheelSettings.Veteran()
        assertTrue(SettingsCommandId.PEDALS_MODE.isAvailable(state))
        assertFalse(SettingsCommandId.PEDAL_HARDNESS.isAvailable(state))
    }

    @Test
    fun `Veteran state reporting continuous pedal hardness hides 3-step PedalsMode`() {
        val state: WheelSettings = WheelSettings.Veteran(pedalSensitivity = 70)
        assertFalse(SettingsCommandId.PEDALS_MODE.isAvailable(state))
        assertTrue(SettingsCommandId.PEDAL_HARDNESS.isAvailable(state))
    }

    @Test
    fun `non-Veteran wheels keep PedalsMode and never expose Veteran Pedal Hardness`() {
        // InMotionV2 reuses pedalSensitivity for a different semantic; the
        // mutual-exclusion rule must not bleed across wheel types.
        val state: WheelSettings = WheelSettings.InMotionV2(pedalSensitivity = 50)
        assertTrue(SettingsCommandId.PEDALS_MODE.isAvailable(state), "Non-Veteran wheels keep PedalsMode")
        assertFalse(SettingsCommandId.PEDAL_HARDNESS.isAvailable(state), "PEDAL_HARDNESS is Veteran-only")
    }

    @Test
    fun `commands without capability gates remain available across all settings shapes`() {
        // Spot-check a representative sample so a future contributor adding a new
        // command without an isAvailable branch can't accidentally hide it.
        val veteran: WheelSettings = WheelSettings.Veteran()
        val inmotion: WheelSettings = WheelSettings.InMotionV2()
        val none: WheelSettings = WheelSettings.None
        for (id in listOf(
            SettingsCommandId.LIGHT_MODE,
            SettingsCommandId.ALARM_SPEED_1,
            SettingsCommandId.PEDAL_TILT,
            SettingsCommandId.BRAKE_PRESSURE_ALARM,
            SettingsCommandId.SCREEN_BACKLIGHT,
            SettingsCommandId.KEY_TONE,
        )) {
            assertTrue(id.isAvailable(veteran), "$id should remain available on Veteran")
            assertTrue(id.isAvailable(inmotion), "$id should remain available on InMotion V2")
            assertTrue(id.isAvailable(none), "$id should remain available on None")
        }
    }
}
