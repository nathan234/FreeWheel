package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTabTest {

    @Test
    fun `DEVICES and SETTINGS are the only required tabs`() {
        val required = NavigationTab.builtIn.filter { it.isRequired }
        assertEquals(setOf(NavigationTab.Devices, NavigationTab.Settings), required.toSet())
    }

    @Test
    fun `all built-in tabs have unique routes`() {
        val routes = NavigationTab.builtIn.map { it.route }
        assertEquals(routes.toSet().size, routes.size)
    }

    @Test
    fun `all built-in tabs have non-empty labels`() {
        for (tab in NavigationTab.builtIn) {
            assertTrue(tab.label.isNotBlank(), "${tab.id} should have a label")
        }
    }

    @Test
    fun `all built-in tabs have non-empty icon names`() {
        for (tab in NavigationTab.builtIn) {
            assertTrue(tab.iconName.isNotBlank(), "${tab.id} should have an icon name")
        }
    }

    @Test
    fun `there are 8 built-in navigation tabs`() {
        assertEquals(8, NavigationTab.builtIn.size)
    }

    @Test
    fun `DEVICES route is devices`() {
        assertEquals("devices", NavigationTab.Devices.route)
    }

    @Test
    fun `CHART route is chart`() {
        assertEquals("chart", NavigationTab.Chart.route)
    }

    // --- builtInById ---

    @Test
    fun `builtInById returns correct tab for known ID`() {
        assertEquals(NavigationTab.Devices, NavigationTab.builtInById("DEVICES"))
        assertEquals(NavigationTab.Chart, NavigationTab.builtInById("CHART"))
        assertEquals(NavigationTab.Bms, NavigationTab.builtInById("BMS"))
        assertEquals(NavigationTab.Rides, NavigationTab.builtInById("RIDES"))
        assertEquals(NavigationTab.Map, NavigationTab.builtInById("MAP"))
        assertEquals(NavigationTab.WheelSettings, NavigationTab.builtInById("WHEEL_SETTINGS"))
        assertEquals(NavigationTab.Settings, NavigationTab.builtInById("SETTINGS"))
    }

    @Test
    fun `builtInById returns null for unknown ID`() {
        assertNull(NavigationTab.builtInById("UNKNOWN"))
        assertNull(NavigationTab.builtInById("custom_racing"))
    }

    // --- Custom tabs ---

    @Test
    fun `Custom tab has correct route`() {
        val tab = NavigationTab.Custom(id = "my_tab", label = "My Tab", iconName = "speed")
        assertEquals("custom/my_tab", tab.route)
    }

    @Test
    fun `Custom tab is not required`() {
        val tab = NavigationTab.Custom(id = "my_tab", label = "My Tab", iconName = "speed")
        assertFalse(tab.isRequired)
    }

    @Test
    fun `Custom tabs with same data are equal`() {
        val tab1 = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val tab2 = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        assertEquals(tab1, tab2)
    }

    // --- customIcons ---

    @Test
    fun `customIcons contains expected icons`() {
        assertTrue(NavigationTab.customIcons.contains("speed"))
        assertTrue(NavigationTab.customIcons.contains("dashboard"))
        assertTrue(NavigationTab.customIcons.contains("star"))
        assertTrue(NavigationTab.customIcons.size >= 10)
    }

    // --- all built-in tabs have unique IDs ---

    @Test
    fun `all built-in tabs have unique IDs`() {
        val ids = NavigationTab.builtIn.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }
}
