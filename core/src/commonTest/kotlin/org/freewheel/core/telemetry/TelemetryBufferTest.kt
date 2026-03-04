package org.freewheel.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryBufferTest {

    private fun makeSample(
        timestampMs: Long,
        speedKmh: Double = 25.0,
        voltageV: Double = 84.0,
        currentA: Double = 10.0,
        powerW: Double = 840.0,
        temperatureC: Double = 35.0,
        batteryPercent: Double = 75.0,
        pwmPercent: Double = 30.0,
        gpsSpeedKmh: Double = 24.0
    ) = TelemetrySample(
        timestampMs = timestampMs,
        speedKmh = speedKmh,
        voltageV = voltageV,
        currentA = currentA,
        powerW = powerW,
        temperatureC = temperatureC,
        batteryPercent = batteryPercent,
        pwmPercent = pwmPercent,
        gpsSpeedKmh = gpsSpeedKmh
    )

    @Test
    fun `addSampleIfNeeded accepts first sample`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 500)
        val added = buffer.addSampleIfNeeded(makeSample(1000))
        assertTrue(added)
        assertEquals(1, buffer.samples.size)
    }

    @Test
    fun `addSampleIfNeeded throttles samples within interval`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 500)
        buffer.addSampleIfNeeded(makeSample(1000))

        val rejected = buffer.addSampleIfNeeded(makeSample(1200))
        assertFalse(rejected)
        assertEquals(1, buffer.samples.size)
    }

    @Test
    fun `addSampleIfNeeded accepts sample after interval`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 500)
        buffer.addSampleIfNeeded(makeSample(1000))

        val added = buffer.addSampleIfNeeded(makeSample(1500))
        assertTrue(added)
        assertEquals(2, buffer.samples.size)
    }

    @Test
    fun `old samples are trimmed by max age`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100, maxAgeMs = 1000)

        // Add samples over 2 seconds
        for (t in 0L..2000L step 100L) {
            buffer.addSampleIfNeeded(makeSample(t))
        }

        // Samples older than 1000ms before the last sample (2000) should be trimmed
        // Cutoff = 2000 - 1000 = 1000, so samples at t < 1000 are removed
        assertTrue(buffer.samples.all { it.timestampMs >= 1000 })
        assertTrue(buffer.samples.isNotEmpty())
    }

    @Test
    fun `valuesFor extracts correct series`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100, speedKmh = 10.0))
        buffer.addSampleIfNeeded(makeSample(200, speedKmh = 20.0))
        buffer.addSampleIfNeeded(makeSample(300, speedKmh = 30.0))

        val speeds = buffer.valuesFor(MetricType.SPEED)
        assertEquals(listOf(10.0, 20.0, 30.0), speeds)
    }

    @Test
    fun `valuesFor extracts battery series`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100, batteryPercent = 90.0))
        buffer.addSampleIfNeeded(makeSample(200, batteryPercent = 85.0))

        val battery = buffer.valuesFor(MetricType.BATTERY)
        assertEquals(listOf(90.0, 85.0), battery)
    }

    @Test
    fun `valuesFor extracts GPS speed series`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100, gpsSpeedKmh = 15.0))
        buffer.addSampleIfNeeded(makeSample(200, gpsSpeedKmh = 18.0))

        val gps = buffer.valuesFor(MetricType.GPS_SPEED)
        assertEquals(listOf(15.0, 18.0), gps)
    }

    @Test
    fun `statsFor computes min max avg correctly`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100, speedKmh = 10.0))
        buffer.addSampleIfNeeded(makeSample(200, speedKmh = 20.0))
        buffer.addSampleIfNeeded(makeSample(300, speedKmh = 30.0))

        val stats = buffer.statsFor(MetricType.SPEED)
        assertEquals(10.0, stats.min, 0.001)
        assertEquals(30.0, stats.max, 0.001)
        assertEquals(20.0, stats.avg, 0.001)
    }

    @Test
    fun `statsFor on empty buffer returns zeros`() {
        val buffer = TelemetryBuffer()
        val stats = buffer.statsFor(MetricType.SPEED)
        assertEquals(0.0, stats.min, 0.001)
        assertEquals(0.0, stats.max, 0.001)
        assertEquals(0.0, stats.avg, 0.001)
    }

    @Test
    fun `clear resets all state`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100))
        buffer.addSampleIfNeeded(makeSample(200))

        buffer.clear()

        assertEquals(0, buffer.samples.size)

        // After clear, should accept a sample at time 0 again
        assertTrue(buffer.addSampleIfNeeded(makeSample(0)))
    }

    @Test
    fun `effectiveMax returns static max for fixed metrics`() {
        val buffer = TelemetryBuffer()
        assertEquals(50.0, buffer.effectiveMax(MetricType.SPEED), 0.001)
        assertEquals(100.0, buffer.effectiveMax(MetricType.BATTERY), 0.001)
        assertEquals(80.0, buffer.effectiveMax(MetricType.TEMPERATURE), 0.001)
    }

    @Test
    fun `effectiveMax tracks dynamic max for power`() {
        val buffer = TelemetryBuffer(sampleIntervalMs = 100)
        buffer.addSampleIfNeeded(makeSample(100, powerW = 500.0))
        buffer.addSampleIfNeeded(makeSample(200, powerW = 1000.0))

        // Dynamic max should be 1000 * 1.2 = 1200
        assertEquals(1200.0, buffer.effectiveMax(MetricType.POWER), 0.001)
    }

    @Test
    fun `MetricType colorZone returns correct zone for standard metrics`() {
        assertEquals(ColorZone.GREEN, MetricType.SPEED.colorZone(0.3))
        assertEquals(ColorZone.ORANGE, MetricType.SPEED.colorZone(0.6))
        assertEquals(ColorZone.RED, MetricType.SPEED.colorZone(0.8))
    }

    @Test
    fun `MetricType colorZone inverts for battery`() {
        // Battery: green ABOVE 50%, orange 25-50%, red below 25%
        assertEquals(ColorZone.GREEN, MetricType.BATTERY.colorZone(0.7))
        assertEquals(ColorZone.ORANGE, MetricType.BATTERY.colorZone(0.4))
        assertEquals(ColorZone.RED, MetricType.BATTERY.colorZone(0.1))
    }
}
