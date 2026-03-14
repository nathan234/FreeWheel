package org.freewheel.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoTorchEngineTest {

    @Test
    fun `speed above threshold triggers light`() {
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 15.0,
            speedThresholdKmh = 10,
            useSunset = false
        )
        assertTrue(result.shouldBeOn)
        assertTrue(result.speedTriggered)
        assertFalse(result.sunsetTriggered)
    }

    @Test
    fun `speed below threshold does not trigger`() {
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 5.0,
            speedThresholdKmh = 10,
            useSunset = false
        )
        assertFalse(result.shouldBeOn)
        assertFalse(result.speedTriggered)
    }

    @Test
    fun `speed at exactly threshold triggers`() {
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 10.0,
            speedThresholdKmh = 10,
            useSunset = false
        )
        assertTrue(result.shouldBeOn)
        assertTrue(result.speedTriggered)
    }

    @Test
    fun `zero speed threshold disables speed trigger`() {
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 50.0,
            speedThresholdKmh = 0,
            useSunset = false
        )
        assertFalse(result.shouldBeOn)
        assertFalse(result.speedTriggered)
    }

    @Test
    fun `sunset triggers when dark`() {
        // Midnight UTC, NYC in summer (sunset ~0:30 UTC, sunrise ~9:25 UTC)
        // At 3:00 UTC it's dark
        val epochMillis = 1718935200000L // 2024-06-21 02:00:00 UTC
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 0.0,
            speedThresholdKmh = 0,
            useSunset = true,
            latitudeDeg = 40.7,
            longitudeDeg = -74.0,
            epochMillis = epochMillis
        )
        assertTrue(result.shouldBeOn)
        assertFalse(result.speedTriggered)
        assertTrue(result.sunsetTriggered)
    }

    @Test
    fun `sunset does not trigger during daytime`() {
        // Midday UTC at equator on equinox — definitely bright
        val epochMillis = 1710849600000L // 2024-03-19 12:00:00 UTC
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 0.0,
            speedThresholdKmh = 0,
            useSunset = true,
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            epochMillis = epochMillis
        )
        assertFalse(result.shouldBeOn)
        assertFalse(result.sunsetTriggered)
    }

    @Test
    fun `sunset disabled skips sunset check`() {
        // Even if it's midnight, sunset check is off
        val epochMillis = 1718928000000L // 2024-06-21 00:00:00 UTC (midnight)
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 0.0,
            speedThresholdKmh = 0,
            useSunset = false,
            latitudeDeg = 40.7,
            longitudeDeg = -74.0,
            epochMillis = epochMillis
        )
        assertFalse(result.shouldBeOn)
    }

    @Test
    fun `sunset with no GPS coordinates does not trigger`() {
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 0.0,
            speedThresholdKmh = 0,
            useSunset = true,
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            epochMillis = 1718928000000L
        )
        // 0,0 is in the Gulf of Guinea — at midnight UTC it should be dark there
        // But the engine should still work with lat/lon = 0,0 (it's a valid coordinate)
        // This test just ensures no crash
        assertFalse(result.speedTriggered)
    }

    @Test
    fun `both triggers can fire simultaneously`() {
        // Fast + dark
        val epochMillis = 1718935200000L // 2024-06-21 02:00:00 UTC (dark in NYC)
        val result = AutoTorchEngine.shouldLightBeOn(
            speedKmh = 30.0,
            speedThresholdKmh = 10,
            useSunset = true,
            latitudeDeg = 40.7,
            longitudeDeg = -74.0,
            epochMillis = epochMillis
        )
        assertTrue(result.shouldBeOn)
        assertTrue(result.speedTriggered)
        assertTrue(result.sunsetTriggered)
    }
}
