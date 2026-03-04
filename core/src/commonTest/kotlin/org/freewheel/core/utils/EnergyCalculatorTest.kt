package org.freewheel.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyCalculatorTest {

    // ==================== Basic Functionality ====================

    @Test
    fun `empty calculator returns zero`() {
        val calculator = EnergyCalculator()

        assertEquals(0.0, calculator.getPowerHour(1000))
        assertEquals(0.0, calculator.getWhPerKm(1000))
        assertEquals(0, calculator.sampleCount)
    }

    @Test
    fun `single sample returns zero`() {
        val calculator = EnergyCalculator()
        calculator.pushSample(100.0, 0, 1000)

        assertEquals(0.0, calculator.getPowerHour(1000))
        assertEquals(0.0, calculator.getWhPerKm(1000))
    }

    @Test
    fun `two samples can calculate power hour`() {
        val calculator = EnergyCalculator()

        // 1000W for 1 second = 1000 * 1/3600 = 0.278 Wh
        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 10, 1000) // 1 second later, 10m traveled

        val powerHour = calculator.getPowerHour(1000)

        // 1000W average * 1000ms / 3600000 = 0.278 Wh
        assertTrue(powerHour > 0.27 && powerHour < 0.29, "Expected ~0.278 Wh, got $powerHour")
    }

    // ==================== Wh/km Calculation ====================

    @Test
    fun `wh per km calculation`() {
        val calculator = EnergyCalculator()

        // 1000W for 1 second, traveling 100 meters
        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 1000)

        val whPerKm = calculator.getWhPerKm(1000)

        // PowerHour = 1000 * 1000 / 3600000 = 0.278 Wh
        // Distance = 100m = 0.1 km
        // WhPerKm = 0.278 / 0.1 = 2.78 Wh/km
        assertTrue(whPerKm > 2.7 && whPerKm < 2.9, "Expected ~2.78 Wh/km, got $whPerKm")
    }

    @Test
    fun `wh per km returns zero when no distance traveled`() {
        val calculator = EnergyCalculator()

        calculator.pushSample(1000.0, 100, 0)
        calculator.pushSample(1000.0, 100, 1000) // Same distance

        assertEquals(0.0, calculator.getWhPerKm(1000))
    }

    // ==================== Rolling Window ====================

    @Test
    fun `old samples are pruned after 10 seconds`() {
        val calculator = EnergyCalculator()

        // Add sample at t=0
        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 1000)

        assertEquals(2, calculator.sampleCount)

        // Add sample at t=11 seconds - should prune old samples
        calculator.pushSample(1000.0, 200, 11000)

        // First sample should be pruned
        assertEquals(2, calculator.sampleCount)
    }

    @Test
    fun `power hour uses rolling window`() {
        val calculator = EnergyCalculator()

        // Samples over 12 seconds
        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 3000)
        calculator.pushSample(1000.0, 200, 6000)
        calculator.pushSample(1000.0, 300, 9000)
        calculator.pushSample(1000.0, 400, 12000) // This should prune the first sample

        // Window should now be from 3000 to 12000 (9 seconds)
        val powerHour = calculator.getPowerHour(12000)

        // 1000W average * 9000ms / 3600000 = 2.5 Wh
        assertTrue(powerHour > 2.4 && powerHour < 2.6, "Expected ~2.5 Wh, got $powerHour")
    }

    // ==================== Staleness ====================

    @Test
    fun `returns cached value when data is stale`() {
        val calculator = EnergyCalculator()

        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 1000)

        val freshValue = calculator.getPowerHour(1000)
        assertTrue(freshValue > 0)

        // 3 seconds later (stale threshold is 2 seconds)
        val staleValue = calculator.getPowerHour(4000)

        // Should return the cached value
        assertEquals(freshValue, staleValue)
    }

    // ==================== Caching ====================

    @Test
    fun `values are cached for 1 second`() {
        val calculator = EnergyCalculator()

        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 1000)

        val value1 = calculator.getPowerHour(1000)

        // Add new sample but within cache period
        calculator.pushSample(2000.0, 200, 1500)

        // Should still return cached value
        val value2 = calculator.getPowerHour(1500)
        assertEquals(value1, value2)

        // After cache expires
        val value3 = calculator.getPowerHour(2500)
        assertTrue(value3 != value1, "Value should be recalculated after cache expires")
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears all samples and cache`() {
        val calculator = EnergyCalculator()

        calculator.pushSample(1000.0, 0, 0)
        calculator.pushSample(1000.0, 100, 1000)

        assertTrue(calculator.sampleCount > 0)
        assertTrue(calculator.getPowerHour(1000) > 0)

        calculator.reset()

        assertEquals(0, calculator.sampleCount)
        assertEquals(0.0, calculator.getPowerHour(2000))
    }

    // ==================== Average Power ====================

    @Test
    fun `power hour uses average power across samples`() {
        val calculator = EnergyCalculator()

        // Varying power: 500W, 1000W, 1500W - average = 1000W
        calculator.pushSample(500.0, 0, 0)
        calculator.pushSample(1000.0, 50, 500)
        calculator.pushSample(1500.0, 100, 1000)

        val powerHour = calculator.getPowerHour(1000)

        // Average power = 1000W, time = 1 second
        // Expected = 1000 * 1000 / 3600000 = 0.278 Wh
        assertTrue(powerHour > 0.27 && powerHour < 0.29, "Expected ~0.278 Wh, got $powerHour")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles zero power samples`() {
        val calculator = EnergyCalculator()

        calculator.pushSample(0.0, 0, 0)
        calculator.pushSample(0.0, 100, 1000)

        assertEquals(0.0, calculator.getPowerHour(1000))
    }

    @Test
    fun `handles negative power from regeneration`() {
        val calculator = EnergyCalculator()

        // Negative power = regenerative braking
        calculator.pushSample(-500.0, 0, 0)
        calculator.pushSample(-500.0, 100, 1000)

        val powerHour = calculator.getPowerHour(1000)

        // Should calculate negative energy (regeneration)
        assertTrue(powerHour < 0, "Regeneration should result in negative Wh")
    }

    @Test
    fun `handles rapid sample pushes`() {
        val calculator = EnergyCalculator()

        // Push many samples rapidly
        for (i in 0..100) {
            calculator.pushSample(1000.0, i * 10, i * 100L)
        }

        // Should still calculate correctly
        val powerHour = calculator.getPowerHour(10000)
        assertTrue(powerHour > 0)
    }
}
