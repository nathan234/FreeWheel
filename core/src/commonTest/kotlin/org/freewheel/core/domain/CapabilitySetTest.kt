package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitySetTest {

    @Test
    fun `empty CapabilitySet is unresolved`() {
        val cap = CapabilitySet()
        assertFalse(cap.isResolved)
        assertTrue(cap.supportedCommands.isEmpty())
    }

    @Test
    fun `supports returns true for included commands`() {
        val cap = CapabilitySet(
            supportedCommands = setOf(SettingsCommandId.LIGHT_MODE, SettingsCommandId.PEDALS_MODE),
            isResolved = true
        )
        assertTrue(cap.supports(SettingsCommandId.LIGHT_MODE))
        assertTrue(cap.supports(SettingsCommandId.PEDALS_MODE))
        assertFalse(cap.supports(SettingsCommandId.CALIBRATE))
    }

    @Test
    fun `mergeWith takes union of commands - monotonic expansion`() {
        val old = CapabilitySet(
            supportedCommands = setOf(SettingsCommandId.LIGHT_MODE, SettingsCommandId.PEDALS_MODE),
            detectedModel = "Model A",
            isResolved = true
        )
        val newer = CapabilitySet(
            supportedCommands = setOf(SettingsCommandId.PEDALS_MODE, SettingsCommandId.CALIBRATE),
            detectedModel = "Model A",
            isResolved = true
        )
        val merged = old.mergeWith(newer)

        assertEquals(3, merged.supportedCommands.size)
        assertTrue(merged.supports(SettingsCommandId.LIGHT_MODE))
        assertTrue(merged.supports(SettingsCommandId.PEDALS_MODE))
        assertTrue(merged.supports(SettingsCommandId.CALIBRATE))
        assertTrue(merged.isResolved)
    }

    @Test
    fun `mergeWith never removes commands`() {
        val full = CapabilitySet(
            supportedCommands = setOf(
                SettingsCommandId.LIGHT_MODE,
                SettingsCommandId.CALIBRATE,
                SettingsCommandId.HIGH_SPEED_MODE
            ),
            isResolved = true
        )
        val reduced = CapabilitySet(
            supportedCommands = setOf(SettingsCommandId.LIGHT_MODE),
            isResolved = true
        )
        val merged = full.mergeWith(reduced)

        // All original commands are preserved
        assertEquals(3, merged.supportedCommands.size)
        assertTrue(merged.supports(SettingsCommandId.HIGH_SPEED_MODE))
    }

    @Test
    fun `mergeWith preserves isResolved once set`() {
        val resolved = CapabilitySet(isResolved = true)
        val unresolved = CapabilitySet(isResolved = false)
        assertTrue(resolved.mergeWith(unresolved).isResolved)
    }

    @Test
    fun `mergeWith takes newer model name when non-empty`() {
        val old = CapabilitySet(detectedModel = "Old Model")
        val newer = CapabilitySet(detectedModel = "New Model")
        assertEquals("New Model", old.mergeWith(newer).detectedModel)
    }

    @Test
    fun `mergeWith keeps old model when newer is empty`() {
        val old = CapabilitySet(detectedModel = "Old Model")
        val newer = CapabilitySet(detectedModel = "")
        assertEquals("Old Model", old.mergeWith(newer).detectedModel)
    }

    @Test
    fun `mergeWith takes max firmware level`() {
        val old = CapabilitySet(firmwareLevel = 3)
        val newer = CapabilitySet(firmwareLevel = 5)
        assertEquals(5, old.mergeWith(newer).firmwareLevel)
    }

    @Test
    fun `mergeWith control overrides from newer win`() {
        val override1 = ControlSpec.Slider("Speed", 10, 50, "km/h", 30, SettingsCommandId.MAX_SPEED)
        val override2 = ControlSpec.Slider("Speed", 5, 80, "km/h", 40, SettingsCommandId.MAX_SPEED)

        val old = CapabilitySet(controlOverrides = mapOf(SettingsCommandId.MAX_SPEED to override1))
        val newer = CapabilitySet(controlOverrides = mapOf(SettingsCommandId.MAX_SPEED to override2))
        val merged = old.mergeWith(newer)

        val slider = merged.controlOverrides[SettingsCommandId.MAX_SPEED] as ControlSpec.Slider
        assertEquals(80, slider.max) // newer override wins
    }

    // ==================== CapabilityMap.resolveAt ====================

    @Test
    fun `resolveAt filters commands by firmware level`() {
        val map: CapabilityMap = mapOf(
            SettingsCommandId.LIGHT_MODE to 0,
            SettingsCommandId.PEDALS_MODE to 0,
            SettingsCommandId.CALIBRATE to 3,
            SettingsCommandId.HIGH_SPEED_MODE to 3,
            SettingsCommandId.SCREEN_BACKLIGHT to 5,
        )

        // Firmware level 0: only level-0 commands
        val cap0 = map.resolveAt(firmwareLevel = 0)
        assertEquals(2, cap0.supportedCommands.size)
        assertTrue(cap0.supports(SettingsCommandId.LIGHT_MODE))
        assertFalse(cap0.supports(SettingsCommandId.CALIBRATE))

        // Firmware level 3: level-0 and level-3 commands
        val cap3 = map.resolveAt(firmwareLevel = 3)
        assertEquals(4, cap3.supportedCommands.size)
        assertTrue(cap3.supports(SettingsCommandId.CALIBRATE))
        assertFalse(cap3.supports(SettingsCommandId.SCREEN_BACKLIGHT))

        // Firmware level 5: all commands
        val cap5 = map.resolveAt(firmwareLevel = 5)
        assertEquals(5, cap5.supportedCommands.size)
        assertTrue(cap5.supports(SettingsCommandId.SCREEN_BACKLIGHT))
    }

    @Test
    fun `resolveAt sets isResolved and metadata`() {
        val map: CapabilityMap = mapOf(SettingsCommandId.LIGHT_MODE to 0)
        val cap = map.resolveAt(
            firmwareLevel = 1,
            detectedModel = "Test Model",
            firmwareVersion = "1.2.3"
        )
        assertTrue(cap.isResolved)
        assertEquals("Test Model", cap.detectedModel)
        assertEquals("1.2.3", cap.firmwareVersion)
        assertEquals(1, cap.firmwareLevel)
    }
}
