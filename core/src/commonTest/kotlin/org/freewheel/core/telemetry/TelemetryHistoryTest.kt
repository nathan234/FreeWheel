package org.freewheel.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-memory TelemetryFileIO for testing without platform filesystem.
 */
private class FakeFileIO : TelemetryFileIO {
    val files = mutableMapOf<String, String>()

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, content: String): Boolean {
        files[path] = content
        return true
    }

    override fun delete(path: String): Boolean = files.remove(path) != null

    override fun exists(path: String): Boolean = files.containsKey(path)
}

class TelemetryHistoryTest {

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
    fun `addSample accepts first sample`() {
        val history = TelemetryHistory(FakeFileIO(), downsampleThreshold = 1000)
        assertTrue(history.addSample(makeSample(1000)))
        assertEquals(1, history.samples.size)
    }

    @Test
    fun `addSample throttles within interval`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 500, downsampleThreshold = 1000)
        history.addSample(makeSample(1000))
        assertFalse(history.addSample(makeSample(1200)))
        assertEquals(1, history.samples.size)
    }

    @Test
    fun `addSample accepts after interval`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 500, downsampleThreshold = 1000)
        history.addSample(makeSample(1000))
        assertTrue(history.addSample(makeSample(1500)))
        assertEquals(2, history.samples.size)
    }

    @Test
    fun `downsample preserves recent 500ms samples`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 500, downsampleThreshold = 1000)

        // Add 10 samples at 500ms intervals within the last 5 seconds (Tier 1)
        val now = 100_000L
        for (i in 0 until 10) {
            history.addSample(makeSample(now - 5000 + i * 500L))
        }
        assertEquals(10, history.samples.size)

        // Downsample should keep all (they're within Tier 1 at 500ms interval)
        history.downsample(now)
        assertEquals(10, history.samples.size)
    }

    @Test
    fun `downsample reduces tier 2 samples to 5s intervals`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 500, downsampleThreshold = 1000)

        val now = 1_000_000L
        // Add samples every 1s from 10min ago to 6min ago (in tier 2: 5min-1hr)
        for (t in (now - 600_000L) until (now - 360_000L) step 1000L) {
            history.addSample(makeSample(t))
        }
        val beforeCount = history.samples.size

        history.downsample(now)

        // After downsample, should have significantly fewer samples
        assertTrue(history.samples.size < beforeCount, "Expected fewer samples after downsample, got ${history.samples.size} vs $beforeCount")
        // Each pair of consecutive samples should be >= 5s apart in tier 2
        val tier2Samples = history.samples.filter { now - it.timestampMs in 300_001..3_600_000 }
        for (i in 1 until tier2Samples.size) {
            val gap = tier2Samples[i].timestampMs - tier2Samples[i - 1].timestampMs
            assertTrue(gap >= 5000, "Tier 2 gap should be >= 5000ms, got $gap")
        }
    }

    @Test
    fun `downsample reduces tier 3 samples to 60s intervals`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 500, downsampleThreshold = 10000)

        val now = 100_000_000L
        // Add samples every 10s from 2hr ago to 1.5hr ago (in tier 3: 1hr-24hr)
        for (t in (now - 7_200_000L) until (now - 5_400_000L) step 10_000L) {
            history.addSample(makeSample(t))
        }

        history.downsample(now)

        val tier3Samples = history.samples.filter { now - it.timestampMs > 3_600_000 }
        for (i in 1 until tier3Samples.size) {
            val gap = tier3Samples[i].timestampMs - tier3Samples[i - 1].timestampMs
            assertTrue(gap >= 60_000, "Tier 3 gap should be >= 60000ms, got $gap")
        }
    }

    @Test
    fun `samples older than 24h are dropped`() {
        val maxAge = 86_400_000L
        val history = TelemetryHistory(FakeFileIO(), maxAgeMs = maxAge, downsampleThreshold = 1000)

        val now = 200_000_000L
        history.addSample(makeSample(now - maxAge - 1000))  // expired
        history.addSample(makeSample(now - 1000))            // recent

        history.downsample(now)

        assertEquals(1, history.samples.size)
        assertEquals(now - 1000, history.samples[0].timestampMs)
    }

    @Test
    fun `samplesForRange returns correct subsets`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 1000, downsampleThreshold = 10000)

        val now = 100_000_000L
        // Add samples: one recent, one 30min ago, one 2hr ago
        history.addSample(makeSample(now - 7_200_000))  // 2hr ago
        history.addSample(makeSample(now - 1_800_000))  // 30min ago
        history.addSample(makeSample(now - 60_000))      // 1min ago
        history.addSample(makeSample(now))               // now

        val fiveMin = history.samplesForRange(ChartTimeRange.FIVE_MINUTES)
        assertEquals(2, fiveMin.size)  // 1min ago + now

        val oneHour = history.samplesForRange(ChartTimeRange.ONE_HOUR)
        assertEquals(3, oneHour.size)  // 30min ago + 1min ago + now

        val twentyFourHour = history.samplesForRange(ChartTimeRange.TWENTY_FOUR_HOURS)
        assertEquals(4, twentyFourHour.size)  // all
    }

    @Test
    fun `statsForRange computes correct statistics`() {
        val history = TelemetryHistory(FakeFileIO(), sampleIntervalMs = 1000, downsampleThreshold = 10000)

        val now = 100_000L
        history.addSample(makeSample(now - 2000, speedKmh = 10.0))
        history.addSample(makeSample(now - 1000, speedKmh = 20.0))
        history.addSample(makeSample(now, speedKmh = 30.0))

        val stats = history.statsForRange(MetricType.SPEED, ChartTimeRange.FIVE_MINUTES)
        assertEquals(10.0, stats.min, 0.001)
        assertEquals(30.0, stats.max, 0.001)
        assertEquals(20.0, stats.avg, 0.001)
    }

    @Test
    fun `statsForRange on empty returns zeros`() {
        val history = TelemetryHistory(FakeFileIO())
        val stats = history.statsForRange(MetricType.SPEED, ChartTimeRange.FIVE_MINUTES)
        assertEquals(0.0, stats.min, 0.001)
        assertEquals(0.0, stats.max, 0.001)
        assertEquals(0.0, stats.avg, 0.001)
    }

    @Test
    fun `clear removes all samples`() {
        val history = TelemetryHistory(FakeFileIO(), downsampleThreshold = 1000)
        history.addSample(makeSample(1000))
        history.addSample(makeSample(2000))
        history.clear()
        assertTrue(history.samples.isEmpty())
    }

    @Test
    fun `CSV round-trip via save and load`() {
        val fileIO = FakeFileIO()
        val path = "/test/history.csv"

        val history1 = TelemetryHistory(fileIO, sampleIntervalMs = 500, downsampleThreshold = 1000)
        history1.loadForWheel(path, nowMs = 100_000L)
        history1.addSample(makeSample(99_000, speedKmh = 15.0))
        history1.addSample(makeSample(99_500, speedKmh = 20.0))
        history1.addSample(makeSample(100_000, speedKmh = 25.0))
        history1.save()

        // Load in a new history instance
        val history2 = TelemetryHistory(fileIO, sampleIntervalMs = 500, downsampleThreshold = 1000)
        history2.loadForWheel(path, nowMs = 100_000L)

        assertEquals(3, history2.samples.size)
        assertEquals(15.0, history2.samples[0].speedKmh, 0.001)
        assertEquals(25.0, history2.samples[2].speedKmh, 0.001)
    }

    @Test
    fun `loadForWheel with missing file starts empty`() {
        val history = TelemetryHistory(FakeFileIO())
        history.loadForWheel("/nonexistent/file.csv", nowMs = 100_000L)
        assertTrue(history.samples.isEmpty())
    }

    @Test
    fun `deleteFile removes file and clears data`() {
        val fileIO = FakeFileIO()
        val path = "/test/history.csv"
        fileIO.files[path] = "some data"

        val history = TelemetryHistory(fileIO)
        history.loadForWheel(path, nowMs = 100_000L)
        history.addSample(makeSample(100_000))
        history.deleteFile()

        assertTrue(history.samples.isEmpty())
        assertFalse(fileIO.exists(path))
    }

    @Test
    fun `downsample triggers automatically after threshold additions`() {
        val fileIO = FakeFileIO()
        val path = "/test/history.csv"
        // Threshold of 5 to trigger downsample quickly
        val history = TelemetryHistory(fileIO, sampleIntervalMs = 500, downsampleThreshold = 5)
        history.loadForWheel(path, nowMs = 100_000L)

        // Add 5 samples to trigger downsample + save
        for (i in 0 until 5) {
            history.addSample(makeSample(100_000L + i * 500))
        }

        // File should have been saved
        assertTrue(fileIO.exists(path))
    }
}
