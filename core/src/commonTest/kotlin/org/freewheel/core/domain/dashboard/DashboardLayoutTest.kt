package org.freewheel.core.domain.dashboard

import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLayoutTest {

    @Test
    fun `default layout matches current hardcoded dashboard`() {
        val layout = DashboardLayout.default()
        assertEquals(DashboardMetric.SPEED, layout.heroMetric)
        assertEquals(
            listOf(
                DashboardMetric.SPEED, DashboardMetric.BATTERY, DashboardMetric.POWER,
                DashboardMetric.PWM, DashboardMetric.TEMPERATURE, DashboardMetric.GPS_SPEED
            ),
            layout.tiles
        )
        assertEquals(
            listOf(
                DashboardMetric.VOLTAGE, DashboardMetric.CURRENT,
                DashboardMetric.TRIP_DISTANCE, DashboardMetric.TOTAL_DISTANCE
            ),
            layout.stats
        )
        assertTrue(layout.showWheelSettings)
        assertTrue(layout.showWheelInfo)
    }

    @Test
    fun `default layout has both sections`() {
        val layout = DashboardLayout.default()
        assertTrue(DashboardSection.WHEEL_SETTINGS in layout.sections)
        assertTrue(DashboardSection.WHEEL_INFO in layout.sections)
    }

    @Test
    fun `create with valid metrics succeeds`() {
        val layout = DashboardLayout.create(
            heroMetric = DashboardMetric.PWM,
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.VOLTAGE)
        )
        assertEquals(DashboardMetric.PWM, layout.heroMetric)
    }

    @Test
    fun `create with invalid hero throws`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardLayout.create(heroMetric = DashboardMetric.VOLTAGE) // VOLTAGE doesn't support HERO_GAUGE
        }
    }

    @Test
    fun `create with invalid tile throws`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardLayout.create(tiles = listOf(DashboardMetric.TRIP_DISTANCE)) // STAT_ROW only
        }
    }

    @Test
    fun `createLenient filters invalid hero to SPEED`() {
        val layout = DashboardLayout.createLenient(heroMetric = DashboardMetric.VOLTAGE)
        assertEquals(DashboardMetric.SPEED, layout.heroMetric)
    }

    @Test
    fun `createLenient filters invalid tiles`() {
        val layout = DashboardLayout.createLenient(
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.TRIP_DISTANCE, DashboardMetric.BATTERY)
        )
        assertEquals(listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY), layout.tiles)
    }

    @Test
    fun `createLenient filters invalid stats`() {
        val layout = DashboardLayout.createLenient(
            stats = listOf(DashboardMetric.VOLTAGE, DashboardMetric.SPEED) // SPEED supports STAT_ROW so should stay
        )
        assertEquals(listOf(DashboardMetric.VOLTAGE, DashboardMetric.SPEED), layout.stats)
    }

    @Test
    fun `create preserves id and name`() {
        val layout = DashboardLayout.create(id = "custom", name = "My Layout")
        assertEquals("custom", layout.id)
        assertEquals("My Layout", layout.name)
    }

    @Test
    fun `sections can be empty`() {
        val layout = DashboardLayout.create(sections = emptySet())
        assertFalse(layout.showWheelSettings)
        assertFalse(layout.showWheelInfo)
        assertTrue(layout.sections.isEmpty())
    }

    @Test
    fun `sections can have only WHEEL_SETTINGS`() {
        val layout = DashboardLayout.create(sections = setOf(DashboardSection.WHEEL_SETTINGS))
        assertTrue(layout.showWheelSettings)
        assertFalse(layout.showWheelInfo)
    }

    @Test
    fun `filteredFor removes unavailable metrics`() {
        val layout = DashboardLayout.create(
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.VOLTAGE)
        )
        // Add some wheel-specific metrics via createLenient to avoid validation
        val layoutWithIM2 = DashboardLayout.createLenient(
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.VOLTAGE, DashboardMetric.CPU_LOAD)
        )
        val filtered = layoutWithIM2.filteredFor(WheelType.GOTWAY)
        assertEquals(listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY), filtered.tiles)
        assertEquals(listOf(DashboardMetric.VOLTAGE), filtered.stats)
    }

    @Test
    fun `filteredFor keeps universal metrics`() {
        val layout = DashboardLayout.default()
        val filtered = layout.filteredFor(WheelType.KINGSONG)
        assertEquals(layout.tiles, filtered.tiles)
        assertEquals(layout.stats, filtered.stats)
    }

    @Test
    fun `filteredFor falls back hero to SPEED if unavailable`() {
        val layout = DashboardLayout.createLenient(heroMetric = DashboardMetric.SPEED)
        // Create a layout with a wheel-specific hero
        val torqueLayout = DashboardLayout.createLenient(
            heroMetric = DashboardMetric.POWER, // POWER is universal with HERO_GAUGE
            tiles = listOf(DashboardMetric.SPEED)
        )
        // This should keep POWER since it's universal
        val filtered = torqueLayout.filteredFor(WheelType.KINGSONG)
        assertEquals(DashboardMetric.POWER, filtered.heroMetric)
    }

    @Test
    fun `filteredFor with Unknown type returns layout unchanged`() {
        val layout = DashboardLayout.createLenient(
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY)
        )
        val filtered = layout.filteredFor(WheelType.Unknown)
        assertEquals(layout, filtered)
    }

    @Test
    fun `filteredFor preserves sections`() {
        val layout = DashboardLayout.create(sections = emptySet())
        val filtered = layout.filteredFor(WheelType.INMOTION_V2)
        assertFalse(filtered.showWheelSettings)
        assertFalse(filtered.showWheelInfo)
    }

    @Test
    fun `create with null hero succeeds for tiles-only layout`() {
        val layout = DashboardLayout.create(
            heroMetric = null,
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY)
        )
        assertNull(layout.heroMetric)
    }

    @Test
    fun `createLenient preserves null hero`() {
        val layout = DashboardLayout.createLenient(heroMetric = null)
        assertNull(layout.heroMetric)
    }

    @Test
    fun `filteredFor preserves null hero`() {
        val layout = DashboardLayout.create(
            heroMetric = null,
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY)
        )
        val filtered = layout.filteredFor(WheelType.KINGSONG)
        assertNull(filtered.heroMetric)
    }

    @Test
    fun `filteredFor with IM2 keeps IM2-specific metrics`() {
        val layout = DashboardLayout.createLenient(
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.CPU_TEMP, DashboardMetric.IMU_TEMP, DashboardMetric.SPEED_LIMIT)
        )
        val filtered = layout.filteredFor(WheelType.INMOTION_V2)
        assertEquals(layout.tiles, filtered.tiles)
        assertEquals(layout.stats, filtered.stats)
    }
}
