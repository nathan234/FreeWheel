package com.cooper.wheellog.core.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DemoDataProvider].
 *
 * The provider generates realistic wheel telemetry at 10Hz (100ms delay)
 * with a 60-second ride cycle: accelerate → cruise → decelerate → stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoDataProviderTest {

    @Test
    fun `start begins emitting states`() = runTest {
        val provider = DemoDataProvider()
        assertFalse(provider.isRunning)

        provider.start(this)
        assertTrue(provider.isRunning)

        // Advance past first tick (100ms delay)
        advanceTimeBy(150)
        runCurrent()

        val state = provider.wheelState.value
        assertTrue(state.speed > 0 || state.batteryLevel == 85, "State should be updated after start")

        provider.stop()
        assertFalse(provider.isRunning)
    }

    @Test
    fun `stop halts emission and resets state`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)
        advanceTimeBy(500)
        runCurrent()

        assertTrue(provider.isRunning)
        assertTrue(provider.wheelState.value.speed > 0, "Speed should be > 0 during run")

        provider.stop()

        assertFalse(provider.isRunning)
        // After stop, state is reset to default WheelState
        assertEquals(0, provider.wheelState.value.speed, "Speed should be 0 after stop")
    }

    @Test
    fun `speed follows 60-second cycle - acceleration phase`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // In the first 12 seconds (120 ticks) of a 60-second cycle,
        // the speed accelerates from 0 up to 25 km/h (phase < 0.2)
        advanceTimeBy(5_000) // 5 seconds = 50 ticks
        runCurrent()

        val state = provider.wheelState.value
        assertTrue(state.speed > 0, "Speed should increase during acceleration phase")
        // Speed in internal units (1/100), so 25 km/h = 2500
        assertTrue(state.speed <= 2500, "Speed should not exceed 25 km/h during acceleration")

        provider.stop()
    }

    @Test
    fun `speed reaches cruise phase`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // At 15 seconds (150 ticks), phase = 150/600 = 0.25, which is in cruise phase (0.2-0.7)
        // Cruise speed oscillates around 22 km/h ± 3 km/h
        advanceTimeBy(15_000)
        runCurrent()

        val state = provider.wheelState.value
        val speedKmh = state.speed / 100.0
        assertTrue(speedKmh >= 15.0, "Speed should be >= 15 km/h during cruise (got $speedKmh)")
        assertTrue(speedKmh <= 30.0, "Speed should be <= 30 km/h during cruise (got $speedKmh)")

        provider.stop()
    }

    @Test
    fun `battery drains over time`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // Battery starts at 85 and drains 1% every 100 ticks (10 seconds)
        // After 30 seconds (300 ticks) → 3 drains → battery should be ~82
        advanceTimeBy(30_000)
        runCurrent()

        val battery = provider.wheelState.value.batteryLevel
        assertTrue(battery < 85, "Battery should drain from 85 (got $battery)")
        assertTrue(battery >= 80, "Battery should not drain below 80 in 30s (got $battery)")

        provider.stop()
    }

    @Test
    fun `BMS snapshot is generated`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // BMS is generated on tick 1 and every 10 ticks
        advanceTimeBy(200) // 2 ticks
        runCurrent()

        val bms = provider.wheelState.value.bms1
        assertNotNull(bms, "BMS snapshot should be non-null after first tick")
        assertEquals(16, bms.cellNum, "BMS should have 16 cells")
        assertEquals("DEMO-BMS-001", bms.serialNumber)
        assertTrue(bms.voltage > 0, "BMS voltage should be positive")

        provider.stop()
    }

    @Test
    fun `distance accumulates while speed is positive`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // After a few seconds of acceleration, distance should increase
        advanceTimeBy(5_000)
        runCurrent()
        val dist1 = provider.wheelState.value.wheelDistance

        advanceTimeBy(5_000)
        runCurrent()
        val dist2 = provider.wheelState.value.wheelDistance

        assertTrue(dist2 > dist1, "Distance should accumulate (got $dist1 → $dist2)")

        provider.stop()
    }

    @Test
    fun `double start is idempotent`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)
        advanceTimeBy(200)
        runCurrent()

        // Start again — should be a no-op
        provider.start(this)
        advanceTimeBy(200)
        runCurrent()

        assertTrue(provider.isRunning)
        provider.stop()
    }

    @Test
    fun `model name is set correctly`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)
        advanceTimeBy(150)
        runCurrent()

        assertEquals("Demo Wheel", provider.wheelState.value.model)
        assertEquals("Demo", provider.wheelState.value.name)

        provider.stop()
    }

    @Test
    fun `temperature rises with speed`() = runTest {
        val provider = DemoDataProvider()
        provider.start(this)

        // At rest, base temp is ~25°C (2500 internal units)
        // With speed, temp rises
        advanceTimeBy(15_000) // cruise phase
        runCurrent()

        val temp = provider.wheelState.value.temperature
        // Base 25°C + speed contribution (~10°C at full speed) + sine variation (±2°C)
        // In internal units: 2500 + up to ~1200 = ~3700
        assertTrue(temp > 2500, "Temperature should be above 25°C base (got ${temp / 100.0}°C)")

        provider.stop()
    }
}
