package org.freewheel.compose

import com.google.common.truth.Truth.assertThat
import org.freewheel.core.domain.dashboard.NavigationConfig
import org.freewheel.core.domain.dashboard.NavigationTab
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for NavigationConfig validation logic.
 * Pure JUnit4 — no Robolectric or Compose needed.
 */
@RunWith(JUnit4::class)
class NavigationConfigUITest {

    @Test
    fun `default config has 4 tabs`() {
        val config = NavigationConfig()
        assertThat(config.tabs).hasSize(4)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `adding Chart tab creates 4-tab config`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.Devices,
                NavigationTab.Chart,
                NavigationTab.Rides,
                NavigationTab.Settings
            )
        )
        assertThat(config.tabs).hasSize(4)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `config without Devices is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Chart, NavigationTab.Settings)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with 1 tab is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with more than 5 tabs is invalid`() {
        val config = NavigationConfig(
            tabs = NavigationTab.builtIn
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with duplicates is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, NavigationTab.Settings, NavigationTab.Settings)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `5-tab config is valid`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.Devices,
                NavigationTab.Chart,
                NavigationTab.Bms,
                NavigationTab.Rides,
                NavigationTab.Settings
            )
        )
        assertThat(config.tabs).hasSize(5)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `all built-in tabs have non-empty labels`() {
        for (tab in NavigationTab.builtIn) {
            assertThat(tab.label).isNotEmpty()
        }
    }

    @Test
    fun `all built-in tabs have non-empty icon names`() {
        for (tab in NavigationTab.builtIn) {
            assertThat(tab.iconName).isNotEmpty()
        }
    }

    @Test
    fun `Devices tab always present in default config`() {
        val config = NavigationConfig()
        assertThat(config.tabs).contains(NavigationTab.Devices)
    }

    // --- Custom tab tests ---

    @Test
    fun `config with custom tab is valid`() {
        val custom = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom, NavigationTab.Settings)
        )
        assertThat(config.isValid()).isTrue()
        assertThat(config.customTabs).hasSize(1)
    }

    @Test
    fun `config with duplicate custom tab IDs is invalid`() {
        val custom1 = NavigationTab.Custom(id = "racing", label = "Racing", iconName = "speed")
        val custom2 = NavigationTab.Custom(id = "racing", label = "Racing 2", iconName = "bolt")
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.Devices, custom1, custom2, NavigationTab.Settings)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `custom tab has correct route`() {
        val custom = NavigationTab.Custom(id = "my_tab", label = "My Tab", iconName = "star")
        assertThat(custom.route).isEqualTo("custom/my_tab")
        assertThat(custom.isRequired).isFalse()
    }
}
