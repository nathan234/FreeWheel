package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationConfigTest {

    @Test
    fun `default config matches current 4-tab layout`() {
        val config = NavigationConfig()
        assertEquals(
            listOf(
                NavigationTab.Devices,
                NavigationTab.Map,
                NavigationTab.Rides,
                NavigationTab.Settings
            ),
            config.tabs
        )
    }

    @Test
    fun `default config is valid`() {
        assertTrue(NavigationConfig().isValid())
    }

    @Test
    fun `config without DEVICES is invalid`() {
        val config = NavigationConfig(tabs = listOf(NavigationTab.Rides, NavigationTab.Settings))
        assertFalse(config.isValid())
    }

    @Test
    fun `config with only one tab is invalid`() {
        val config = NavigationConfig(tabs = listOf(NavigationTab.Devices))
        assertFalse(config.isValid())
    }

    @Test
    fun `config with 2 tabs is valid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, NavigationTab.Settings)
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config with 5 tabs is valid`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.Devices, NavigationTab.Chart, NavigationTab.Bms,
                NavigationTab.Rides, NavigationTab.Settings
            )
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config with more than 5 tabs is invalid`() {
        val config = NavigationConfig(tabs = NavigationTab.builtIn)
        assertFalse(config.isValid())
    }

    @Test
    fun `config with duplicate tabs is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, NavigationTab.Rides, NavigationTab.Rides)
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `config with empty tabs is invalid`() {
        val config = NavigationConfig(tabs = emptyList())
        assertFalse(config.isValid())
    }

    @Test
    fun `config with all optional tabs plus DEVICES is valid when 5 or fewer`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.Devices, NavigationTab.Chart,
                NavigationTab.Rides, NavigationTab.Settings, NavigationTab.Bms
            )
        )
        assertTrue(config.isValid())
    }

    // --- Warnings ---

    @Test
    fun `default config has no warnings`() {
        val config = NavigationConfig()
        assertTrue(config.warnings().isEmpty())
    }

    @Test
    fun `config without Settings is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, NavigationTab.Rides)
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `config with both required tabs has no warnings`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, NavigationTab.Settings)
        )
        assertTrue(config.warnings().isEmpty())
    }

    // --- Custom tabs ---

    @Test
    fun `config with custom tab is valid`() {
        val custom = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom, NavigationTab.Settings)
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config with duplicate custom tab IDs is invalid`() {
        val custom1 = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val custom2 = NavigationTab.Custom(id = "racing", label = "Racing 2", iconName = "bolt")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom1, custom2, NavigationTab.Settings)
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `customTabs returns only custom tabs`() {
        val custom = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom, NavigationTab.Settings)
        )
        assertEquals(listOf(custom), config.customTabs)
    }

    @Test
    fun `customTabs is empty for built-in only config`() {
        val config = NavigationConfig()
        assertTrue(config.customTabs.isEmpty())
    }

    @Test
    fun `config with mixed built-in and custom tabs at max is valid`() {
        val custom1 = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val custom2 = NavigationTab.Custom(id = "battery", label = "Battery", iconName = "battery_full")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom1, custom2, NavigationTab.Rides, NavigationTab.Settings)
        )
        assertTrue(config.isValid())
        assertEquals(2, config.customTabs.size)
    }
}
