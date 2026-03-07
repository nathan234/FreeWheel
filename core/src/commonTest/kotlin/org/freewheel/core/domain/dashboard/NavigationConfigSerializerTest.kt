package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationConfigSerializerTest {

    @Test
    fun `round-trip preserves default config`() {
        val config = NavigationConfig()
        val serialized = NavigationConfigSerializer.serialize(config)
        val deserialized = NavigationConfigSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(config.tabs, deserialized.tabs)
    }

    @Test
    fun `round-trip preserves custom config`() {
        val config = NavigationConfig(
            tabs = listOf(
                NavigationTab.DEVICES, NavigationTab.CHART,
                NavigationTab.RIDES, NavigationTab.SETTINGS
            )
        )
        val serialized = NavigationConfigSerializer.serialize(config)
        val deserialized = NavigationConfigSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(config.tabs, deserialized.tabs)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(NavigationConfigSerializer.deserialize(""))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(NavigationConfigSerializer.deserialize("   "))
    }

    @Test
    fun `invalid version returns null`() {
        assertNull(NavigationConfigSerializer.deserialize("v99|tabs:DEVICES,SETTINGS"))
    }

    @Test
    fun `unknown tab names are skipped`() {
        val input = "v1|tabs:DEVICES,UNKNOWN_TAB,SETTINGS"
        val result = NavigationConfigSerializer.deserialize(input)
        assertNotNull(result)
        assertEquals(listOf(NavigationTab.DEVICES, NavigationTab.SETTINGS), result.tabs)
    }

    @Test
    fun `config missing DEVICES after unknown-skip returns null`() {
        val input = "v1|tabs:UNKNOWN_TAB,SETTINGS"
        val result = NavigationConfigSerializer.deserialize(input)
        // Only SETTINGS parsed — missing DEVICES, so invalid
        assertNull(result)
    }

    @Test
    fun `serialized format starts with v1`() {
        val serialized = NavigationConfigSerializer.serialize(NavigationConfig())
        assertTrue(serialized.startsWith("v1|"))
    }

    @Test
    fun `config with only DEVICES after skipping unknowns is invalid`() {
        val input = "v1|tabs:DEVICES,FAKE1,FAKE2"
        // Only DEVICES parsed — only 1 tab, so invalid
        assertNull(NavigationConfigSerializer.deserialize(input))
    }

    @Test
    fun `empty tabs value returns null`() {
        val input = "v1|tabs:"
        assertNull(NavigationConfigSerializer.deserialize(input))
    }
}
