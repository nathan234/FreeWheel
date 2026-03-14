package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLayoutSerializerTest {

    @Test
    fun `round-trip preserves default layout`() {
        val layout = DashboardLayout.default()
        val serialized = DashboardLayoutSerializer.serialize(layout)
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(layout.heroMetric, deserialized.heroMetric)
        assertEquals(layout.tiles, deserialized.tiles)
        assertEquals(layout.stats, deserialized.stats)
        assertEquals(layout.showWheelSettings, deserialized.showWheelSettings)
        assertEquals(layout.showWheelInfo, deserialized.showWheelInfo)
    }

    @Test
    fun `round-trip preserves custom layout`() {
        val layout = DashboardLayout.create(
            id = "custom", name = "My Layout",
            heroMetric = DashboardMetric.PWM,
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.VOLTAGE),
            sections = emptySet()
        )
        val serialized = DashboardLayoutSerializer.serialize(layout)
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(layout.heroMetric, deserialized.heroMetric)
        assertEquals(layout.tiles, deserialized.tiles)
        assertEquals(layout.stats, deserialized.stats)
        assertEquals(layout.showWheelSettings, deserialized.showWheelSettings)
        assertEquals(layout.showWheelInfo, deserialized.showWheelInfo)
        assertEquals("custom", deserialized.id)
        assertEquals("My Layout", deserialized.name)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(DashboardLayoutSerializer.deserialize(""))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(DashboardLayoutSerializer.deserialize("   "))
    }

    @Test
    fun `invalid version returns null`() {
        assertNull(DashboardLayoutSerializer.deserialize("v99|hero:SPEED"))
    }

    @Test
    fun `unknown metric names are skipped in tiles`() {
        val input = "v2|hero:SPEED|tiles:SPEED,UNKNOWN_METRIC,BATTERY|stats:VOLTAGE|sections:WHEEL_SETTINGS,WHEEL_INFO"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertEquals(listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY), result.tiles)
    }

    @Test
    fun `unknown hero falls back to SPEED`() {
        val input = "v2|hero:NONEXISTENT|tiles:BATTERY|stats:VOLTAGE|sections:"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertEquals(DashboardMetric.SPEED, result.heroMetric)
    }

    @Test
    fun `missing tiles key uses defaults`() {
        val input = "v2|hero:PWM|stats:VOLTAGE|sections:"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertEquals(DashboardMetric.PWM, result.heroMetric)
        assertEquals(DashboardLayout.DEFAULT_TILES, result.tiles)
    }

    @Test
    fun `missing stats key uses defaults`() {
        val input = "v2|hero:SPEED|tiles:BATTERY|sections:"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertEquals(DashboardLayout.DEFAULT_STATS, result.stats)
    }

    @Test
    fun `serialized format starts with v2`() {
        val serialized = DashboardLayoutSerializer.serialize(DashboardLayout.default())
        assertTrue(serialized.startsWith("v2|"))
    }

    @Test
    fun `round-trip with empty tiles list`() {
        val layout = DashboardLayout.create(tiles = emptyList())
        val serialized = DashboardLayoutSerializer.serialize(layout)
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(emptyList(), deserialized.tiles)
    }

    @Test
    fun `sections round-trip correctly`() {
        val layout = DashboardLayout.create(sections = setOf(DashboardSection.WHEEL_SETTINGS))
        val serialized = DashboardLayoutSerializer.serialize(layout)
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertTrue(deserialized.showWheelSettings)
        assertEquals(false, deserialized.showWheelInfo)
    }

    @Test
    fun `empty sections round-trip correctly`() {
        val layout = DashboardLayout.create(sections = emptySet())
        val serialized = DashboardLayoutSerializer.serialize(layout)
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(false, deserialized.showWheelSettings)
        assertEquals(false, deserialized.showWheelInfo)
    }

    // --- v1 backward compatibility ---

    @Test
    fun `v1 format still deserializes correctly`() {
        val v1Input = "v1|hero:SPEED|tiles:SPEED,BATTERY,POWER|stats:VOLTAGE,CURRENT|settings:1|info:1"
        val result = DashboardLayoutSerializer.deserialize(v1Input)
        assertNotNull(result)
        assertEquals(DashboardMetric.SPEED, result.heroMetric)
        assertEquals(listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY, DashboardMetric.POWER), result.tiles)
        assertEquals(listOf(DashboardMetric.VOLTAGE, DashboardMetric.CURRENT), result.stats)
        assertTrue(result.showWheelSettings)
        assertTrue(result.showWheelInfo)
    }

    @Test
    fun `v1 format with settings off migrates to empty sections`() {
        val v1Input = "v1|hero:PWM|tiles:SPEED|stats:VOLTAGE|settings:0|info:0"
        val result = DashboardLayoutSerializer.deserialize(v1Input)
        assertNotNull(result)
        assertEquals(DashboardMetric.PWM, result.heroMetric)
        assertEquals(false, result.showWheelSettings)
        assertEquals(false, result.showWheelInfo)
    }

    @Test
    fun `v1 format with partial settings migrates correctly`() {
        val v1Input = "v1|hero:SPEED|tiles:BATTERY|stats:|settings:1|info:0"
        val result = DashboardLayoutSerializer.deserialize(v1Input)
        assertNotNull(result)
        assertTrue(result.showWheelSettings)
        assertEquals(false, result.showWheelInfo)
        assertEquals(emptyList(), result.stats)
    }

    @Test
    fun `id and name are preserved in v2`() {
        val layout = DashboardLayout.create(id = "test-id", name = "Test Name")
        val serialized = DashboardLayoutSerializer.serialize(layout)
        assertTrue(serialized.contains("id:test-id"))
        assertTrue(serialized.contains("name:Test Name"))
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals("test-id", deserialized.id)
        assertEquals("Test Name", deserialized.name)
    }

    @Test
    fun `null hero serializes as NONE and round-trips`() {
        val layout = DashboardLayout.create(
            heroMetric = null,
            tiles = listOf(DashboardMetric.SPEED, DashboardMetric.BATTERY)
        )
        val serialized = DashboardLayoutSerializer.serialize(layout)
        assertTrue(serialized.contains("hero:NONE"))
        val deserialized = DashboardLayoutSerializer.deserialize(serialized)
        assertNotNull(deserialized)
        assertNull(deserialized.heroMetric)
    }

    @Test
    fun `hero NONE in v2 input deserializes to null`() {
        val input = "v2|hero:NONE|tiles:SPEED,BATTERY|stats:VOLTAGE|sections:"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertNull(result.heroMetric)
    }

    @Test
    fun `missing sections key uses defaults`() {
        val input = "v2|hero:SPEED|tiles:BATTERY|stats:VOLTAGE"
        val result = DashboardLayoutSerializer.deserialize(input)
        assertNotNull(result)
        assertTrue(result.showWheelSettings)
        assertTrue(result.showWheelInfo)
    }
}
