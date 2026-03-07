package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DashboardPresetsTest {

    @Test
    fun `all presets have unique IDs`() {
        val ids = DashboardPresets.all().map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `all preset heroes support HERO_GAUGE`() {
        for (preset in DashboardPresets.all()) {
            assertTrue(
                WidgetType.HERO_GAUGE in preset.layout.heroMetric.supportedDisplayTypes,
                "Preset '${preset.name}' hero ${preset.layout.heroMetric} must support HERO_GAUGE"
            )
        }
    }

    @Test
    fun `all preset tiles support GAUGE_TILE`() {
        for (preset in DashboardPresets.all()) {
            for (tile in preset.layout.tiles) {
                assertTrue(
                    WidgetType.GAUGE_TILE in tile.supportedDisplayTypes,
                    "Preset '${preset.name}' tile ${tile.name} must support GAUGE_TILE"
                )
            }
        }
    }

    @Test
    fun `all preset stats support STAT_ROW`() {
        for (preset in DashboardPresets.all()) {
            for (stat in preset.layout.stats) {
                assertTrue(
                    WidgetType.STAT_ROW in stat.supportedDisplayTypes,
                    "Preset '${preset.name}' stat ${stat.name} must support STAT_ROW"
                )
            }
        }
    }

    @Test
    fun `default preset matches DashboardLayout default`() {
        val defaultLayout = DashboardLayout.default()
        val presetLayout = DashboardPresets.default().layout
        assertEquals(defaultLayout.heroMetric, presetLayout.heroMetric)
        assertEquals(defaultLayout.tiles, presetLayout.tiles)
        assertEquals(defaultLayout.stats, presetLayout.stats)
        assertEquals(defaultLayout.showWheelSettings, presetLayout.showWheelSettings)
        assertEquals(defaultLayout.showWheelInfo, presetLayout.showWheelInfo)
    }

    @Test
    fun `there are 5 presets`() {
        assertEquals(5, DashboardPresets.all().size)
    }

    @Test
    fun `all presets have non-empty names`() {
        for (preset in DashboardPresets.all()) {
            assertTrue(preset.name.isNotBlank(), "Preset '${preset.id}' should have a name")
        }
    }

    @Test
    fun `racing preset hides wheel settings and info`() {
        val racing = DashboardPresets.racing()
        assertEquals(false, racing.layout.showWheelSettings)
        assertEquals(false, racing.layout.showWheelInfo)
    }

    @Test
    fun `touring preset shows wheel settings and info`() {
        val touring = DashboardPresets.touring()
        assertEquals(true, touring.layout.showWheelSettings)
        assertEquals(true, touring.layout.showWheelInfo)
    }

    @Test
    fun `diagnostic preset uses PWM as hero`() {
        assertEquals(DashboardMetric.PWM, DashboardPresets.diagnostic().layout.heroMetric)
    }

    @Test
    fun `presets are cached - same instance returned each call`() {
        assertSame(DashboardPresets.default(), DashboardPresets.default())
        assertSame(DashboardPresets.racing(), DashboardPresets.racing())
        assertSame(DashboardPresets.all(), DashboardPresets.all())
    }

    @Test
    fun `preset layouts with ids have non-null id and name`() {
        for (preset in DashboardPresets.all()) {
            if (preset.id != "default") {
                assertTrue(preset.layout.id != null, "Preset '${preset.id}' layout should have an id")
                assertTrue(preset.layout.name != null, "Preset '${preset.id}' layout should have a name")
            }
        }
    }
}
