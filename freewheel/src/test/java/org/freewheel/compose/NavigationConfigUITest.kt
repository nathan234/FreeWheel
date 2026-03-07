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
    fun `default config has 3 tabs`() {
        val config = NavigationConfig()
        assertThat(config.tabs).hasSize(3)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `adding Chart tab creates 4-tab config`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.DEVICES,
                NavigationTab.CHART,
                NavigationTab.RIDES,
                NavigationTab.SETTINGS
            )
        )
        assertThat(config.tabs).hasSize(4)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `config without Devices is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.CHART, NavigationTab.SETTINGS)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with 1 tab is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with 6 tabs is invalid`() {
        val config = NavigationConfig(
            tabs = NavigationTab.entries.toList()
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `config with duplicates is invalid`() {
        val config = NavigationConfig(
            tabs = listOf(NavigationTab.DEVICES, NavigationTab.SETTINGS, NavigationTab.SETTINGS)
        )
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun `5-tab config is valid`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.DEVICES,
                NavigationTab.CHART,
                NavigationTab.BMS,
                NavigationTab.RIDES,
                NavigationTab.SETTINGS
            )
        )
        assertThat(config.tabs).hasSize(5)
        assertThat(config.isValid()).isTrue()
    }

    @Test
    fun `all tabs have non-empty labels`() {
        for (tab in NavigationTab.entries) {
            assertThat(tab.label).isNotEmpty()
        }
    }

    @Test
    fun `all tabs have non-empty icon names`() {
        for (tab in NavigationTab.entries) {
            assertThat(tab.iconName).isNotEmpty()
        }
    }

    @Test
    fun `Devices tab always present in default config`() {
        val config = NavigationConfig()
        assertThat(config.tabs).contains(NavigationTab.DEVICES)
    }
}
