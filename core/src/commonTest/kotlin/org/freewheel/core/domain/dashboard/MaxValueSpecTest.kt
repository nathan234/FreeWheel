package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxValueSpecTest {

    @Test
    fun `Fixed returns value from fixedValueOrNull`() {
        val spec = MaxValueSpec.Fixed(50.0)
        assertEquals(50.0, spec.fixedValueOrNull())
    }

    @Test
    fun `Dynamic returns null from fixedValueOrNull`() {
        val spec = MaxValueSpec.Dynamic()
        assertNull(spec.fixedValueOrNull())
    }

    @Test
    fun `None returns null from fixedValueOrNull`() {
        assertNull(MaxValueSpec.None.fixedValueOrNull())
    }

    @Test
    fun `Fixed requires positive value`() {
        assertFailsWith<IllegalArgumentException> {
            MaxValueSpec.Fixed(0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            MaxValueSpec.Fixed(-5.0)
        }
    }

    @Test
    fun `Dynamic has default minimumDefault of 1000`() {
        val spec = MaxValueSpec.Dynamic()
        assertEquals(1000.0, spec.minimumDefault)
    }

    @Test
    fun `Dynamic accepts custom minimumDefault`() {
        val spec = MaxValueSpec.Dynamic(minimumDefault = 100.0)
        assertEquals(100.0, spec.minimumDefault)
    }

    @Test
    fun `speed metrics use Fixed maxValueSpec`() {
        assertTrue(DashboardMetric.SPEED.maxValueSpec is MaxValueSpec.Fixed)
        assertTrue(DashboardMetric.GPS_SPEED.maxValueSpec is MaxValueSpec.Fixed)
    }

    @Test
    fun `dynamic metrics use Dynamic maxValueSpec`() {
        assertTrue(DashboardMetric.CURRENT.maxValueSpec is MaxValueSpec.Dynamic)
        assertTrue(DashboardMetric.POWER.maxValueSpec is MaxValueSpec.Dynamic)
        assertTrue(DashboardMetric.TORQUE.maxValueSpec is MaxValueSpec.Dynamic)
        assertTrue(DashboardMetric.MOTOR_POWER.maxValueSpec is MaxValueSpec.Dynamic)
        assertTrue(DashboardMetric.PHASE_CURRENT.maxValueSpec is MaxValueSpec.Dynamic)
    }

    @Test
    fun `display-only metrics use None maxValueSpec`() {
        assertTrue(DashboardMetric.TRIP_DISTANCE.maxValueSpec is MaxValueSpec.None)
        assertTrue(DashboardMetric.TOTAL_DISTANCE.maxValueSpec is MaxValueSpec.None)
        assertTrue(DashboardMetric.SPEED_LIMIT.maxValueSpec is MaxValueSpec.None)
        assertTrue(DashboardMetric.CURRENT_LIMIT.maxValueSpec is MaxValueSpec.None)
    }

    @Test
    fun `maxValue backward compat returns 0 for Dynamic and None`() {
        assertEquals(0.0, DashboardMetric.CURRENT.maxValue)
        assertEquals(0.0, DashboardMetric.TRIP_DISTANCE.maxValue)
    }

    @Test
    fun `maxValue backward compat returns fixed value`() {
        assertEquals(50.0, DashboardMetric.SPEED.maxValue)
        assertEquals(100.0, DashboardMetric.BATTERY.maxValue)
        assertEquals(80.0, DashboardMetric.TEMPERATURE.maxValue)
    }
}
