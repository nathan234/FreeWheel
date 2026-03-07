package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationConfigTest {

    @Test
    fun `default config matches current 3-tab layout`() {
        val config = NavigationConfig()
        assertEquals(
            listOf(NavigationTab.DEVICES, NavigationTab.RIDES, NavigationTab.SETTINGS),
            config.tabs
        )
    }

    @Test
    fun `default config is valid`() {
        assertTrue(NavigationConfig().isValid())
    }

    @Test
    fun `config without DEVICES is invalid`() {
        val config = NavigationConfig(tabs = listOf(NavigationTab.RIDES, NavigationTab.SETTINGS))
        assertFalse(config.isValid())
    }

    @Test
    fun `config with only one tab is invalid`() {
        val config = NavigationConfig(tabs = listOf(NavigationTab.DEVICES))
        assertFalse(config.isValid())
    }

    @Test
    fun `config with 2 tabs is valid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES, NavigationTab.SETTINGS)
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config with 5 tabs is valid`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.DEVICES, NavigationTab.CHART, NavigationTab.BMS,
                NavigationTab.RIDES, NavigationTab.SETTINGS
            )
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config with 6 tabs is invalid`() {
        val config = NavigationConfig(tabs = NavigationTab.entries.toList())
        assertFalse(config.isValid())
    }

    @Test
    fun `config with duplicate tabs is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES, NavigationTab.RIDES, NavigationTab.RIDES)
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
                NavigationTab.DEVICES, NavigationTab.CHART,
                NavigationTab.RIDES, NavigationTab.SETTINGS, NavigationTab.BMS
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
    fun `config without Settings shows warning`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES, NavigationTab.RIDES)
        )
        val warnings = config.warnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("Settings"))
    }

    @Test
    fun `config with Settings has no warnings`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES, NavigationTab.SETTINGS)
        )
        assertTrue(config.warnings().isEmpty())
    }
}
