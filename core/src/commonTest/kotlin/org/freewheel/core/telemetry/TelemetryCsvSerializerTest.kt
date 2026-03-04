package org.freewheel.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryCsvSerializerTest {

    private fun makeSample(
        timestampMs: Long = 1000,
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
    fun `round-trip preserves all fields`() {
        val samples = listOf(
            makeSample(timestampMs = 1000, speedKmh = 25.5, voltageV = 84.2),
            makeSample(timestampMs = 1500, speedKmh = 30.0, voltageV = 83.8)
        )
        val csv = TelemetryCsvSerializer.serialize(samples)
        val restored = TelemetryCsvSerializer.deserialize(csv)

        assertEquals(2, restored.size)
        assertEquals(samples[0], restored[0])
        assertEquals(samples[1], restored[1])
    }

    @Test
    fun `empty list serializes to header only`() {
        val csv = TelemetryCsvSerializer.serialize(emptyList())
        assertTrue(csv.startsWith("timestampMs,"))
        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `malformed lines are skipped`() {
        val csv = """
            timestampMs,speedKmh,voltageV,currentA,powerW,temperatureC,batteryPercent,pwmPercent,gpsSpeedKmh
            1000,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0
            this,is,garbage
            not-a-number,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0
            2000,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0
        """.trimIndent()

        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertEquals(2, restored.size)
        assertEquals(1000L, restored[0].timestampMs)
        assertEquals(2000L, restored[1].timestampMs)
    }

    @Test
    fun `too few columns are skipped`() {
        val csv = """
            timestampMs,speedKmh,voltageV,currentA,powerW,temperatureC,batteryPercent,pwmPercent,gpsSpeedKmh
            1000,25.0,84.0
            2000,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0
        """.trimIndent()

        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertEquals(1, restored.size)
        assertEquals(2000L, restored[0].timestampMs)
    }

    @Test
    fun `24h expiry drops old samples`() {
        val now = 100_000_000L
        val maxAge = 86_400_000L // 24h
        val samples = listOf(
            makeSample(timestampMs = now - maxAge - 1000),  // expired
            makeSample(timestampMs = now - maxAge + 1000),  // within range
            makeSample(timestampMs = now - 1000)            // recent
        )
        val csv = TelemetryCsvSerializer.serialize(samples)
        val restored = TelemetryCsvSerializer.deserialize(csv, nowMs = now, maxAgeMs = maxAge)

        assertEquals(2, restored.size)
        assertEquals(now - maxAge + 1000, restored[0].timestampMs)
        assertEquals(now - 1000, restored[1].timestampMs)
    }

    @Test
    fun `future timestamps are dropped`() {
        val now = 100_000L
        val samples = listOf(
            makeSample(timestampMs = now - 1000),
            makeSample(timestampMs = now + 5000)  // future — should be dropped
        )
        val csv = TelemetryCsvSerializer.serialize(samples)
        val restored = TelemetryCsvSerializer.deserialize(csv, nowMs = now, maxAgeMs = 86_400_000L)

        assertEquals(1, restored.size)
        assertEquals(now - 1000, restored[0].timestampMs)
    }

    @Test
    fun `gpsSpeedKmh defaults to zero when missing from older CSV`() {
        val csv = """
            timestampMs,speedKmh,voltageV,currentA,powerW,temperatureC,batteryPercent,pwmPercent
            1000,25.0,84.0,10.0,840.0,35.0,75.0,30.0
        """.trimIndent()

        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertEquals(1, restored.size)
        assertEquals(0.0, restored[0].gpsSpeedKmh, 0.001)
    }

    @Test
    fun `blank lines are skipped`() {
        val csv = """
            timestampMs,speedKmh,voltageV,currentA,powerW,temperatureC,batteryPercent,pwmPercent,gpsSpeedKmh

            1000,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0

        """.trimIndent()

        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertEquals(1, restored.size)
    }

    @Test
    fun `deserialize empty string returns empty list`() {
        val restored = TelemetryCsvSerializer.deserialize("")
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `CSV without header works if lines are valid`() {
        val csv = "1000,25.0,84.0,10.0,840.0,35.0,75.0,30.0,24.0"
        val restored = TelemetryCsvSerializer.deserialize(csv)
        assertEquals(1, restored.size)
        assertEquals(1000L, restored[0].timestampMs)
    }
}
