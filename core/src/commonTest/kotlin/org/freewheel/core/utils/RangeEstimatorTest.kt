package org.freewheel.core.utils

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeEstimatorTest {

    @Test
    fun `basic range estimation`() {
        // Started at 100%, now at 80%, traveled 10km => 0.5 km per percent => 40 km remaining
        val range = RangeEstimator.estimate(
            currentBattery = 80,
            tripDistanceKm = 10.0,
            startBattery = 100
        )
        assertEquals(40.0, range)
    }

    @Test
    fun `returns null when battery used is less than 1 percent`() {
        val range = RangeEstimator.estimate(
            currentBattery = 100,
            tripDistanceKm = 5.0,
            startBattery = 100
        )
        assertNull(range)
    }

    @Test
    fun `returns null when trip distance too short`() {
        val range = RangeEstimator.estimate(
            currentBattery = 90,
            tripDistanceKm = 0.3,
            startBattery = 100
        )
        assertNull(range)
    }

    @Test
    fun `returns null when battery increased`() {
        // Battery went up (regen or charging while riding)
        val range = RangeEstimator.estimate(
            currentBattery = 95,
            tripDistanceKm = 5.0,
            startBattery = 90
        )
        assertNull(range)
    }

    @Test
    fun `low battery gives small range`() {
        // Started at 50%, now at 5%, traveled 45km => 1 km per percent => 5 km remaining
        val range = RangeEstimator.estimate(
            currentBattery = 5,
            tripDistanceKm = 45.0,
            startBattery = 50
        )
        assertEquals(5.0, range)
    }

    @Test
    fun `high efficiency ride`() {
        // Started at 100%, now at 95%, traveled 8km => 1.6 km per percent => 152 km remaining
        val range = RangeEstimator.estimate(
            currentBattery = 95,
            tripDistanceKm = 8.0,
            startBattery = 100
        )
        assertTrue(range != null && abs(range - 152.0) < 0.1)
    }

    @Test
    fun `exact threshold values`() {
        // Exactly 1% used and exactly 0.5 km traveled
        val range = RangeEstimator.estimate(
            currentBattery = 99,
            tripDistanceKm = 0.5,
            startBattery = 100
        )
        assertEquals(49.5, range)
    }
}
