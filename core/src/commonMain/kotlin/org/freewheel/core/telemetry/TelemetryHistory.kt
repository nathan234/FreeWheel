package org.freewheel.core.telemetry

/**
 * Persistent, downsampled 24-hour telemetry history.
 *
 * Works alongside [TelemetryBuffer] — the buffer provides raw 60s real-time data,
 * while this class provides long-term history with tier-based downsampling:
 *
 * | Age from newest | Interval kept | Max samples |
 * |-----------------|---------------|-------------|
 * | 0 – 5 min      | 500ms (raw)   | ~600        |
 * | 5 min – 1 hr   | 5s            | ~660        |
 * | 1 hr – 24 hr   | 60s           | ~1,380      |
 * | **Total**       |               | **~2,640**  |
 *
 * Samples older than 24h are dropped. Downsampling runs periodically.
 */
class TelemetryHistory internal constructor(
    private val fileIO: TelemetryFileIO,
    private val sampleIntervalMs: Long = 500,
    private val maxAgeMs: Long = 86_400_000L,
    private val downsampleThreshold: Int = 60
) {
    /**
     * Public constructor for Swift interop.
     * Kotlin/Native doesn't export default parameter values or internal constructors
     * to ObjC/Swift, so this provides a clean single-param init.
     */
    constructor(fileIO: TelemetryFileIO) : this(
        fileIO = fileIO,
        sampleIntervalMs = 500,
        maxAgeMs = 86_400_000L,
        downsampleThreshold = 60
    )

    private val _samples = mutableListOf<TelemetrySample>()
    val samples: List<TelemetrySample> get() = _samples.toList()

    private var filePath: String? = null
    private var addCount = 0
    private var lastSampleTimeMs: Long? = null

    // Downsample tier boundaries (age from newest) and minimum intervals
    companion object {
        // Tier 1: 0-5min → keep raw (500ms)
        private const val TIER1_AGE_MS = 300_000L
        private const val TIER1_INTERVAL_MS = 500L

        // Tier 2: 5min-1hr → keep every 5s
        private const val TIER2_AGE_MS = 3_600_000L
        private const val TIER2_INTERVAL_MS = 5_000L

        // Tier 3: 1hr-24hr → keep every 60s
        private const val TIER3_INTERVAL_MS = 60_000L
    }

    /**
     * Load history from CSV file for the given wheel.
     * Trims expired samples and future-dated samples.
     */
    fun loadForWheel(path: String, nowMs: Long) {
        filePath = path
        _samples.clear()
        addCount = 0
        lastSampleTimeMs = null

        val csv = fileIO.readText(path) ?: return
        val loaded = TelemetryCsvSerializer.deserialize(csv, nowMs = nowMs, maxAgeMs = maxAgeMs)
        _samples.addAll(loaded)
        if (_samples.isNotEmpty()) {
            lastSampleTimeMs = _samples.last().timestampMs
        }
    }

    /**
     * Add a sample. Throttles to [sampleIntervalMs].
     * Returns true if the sample was added.
     * Triggers downsample every [downsampleThreshold] additions.
     */
    fun addSample(sample: TelemetrySample): Boolean {
        val last = lastSampleTimeMs
        if (last != null && sample.timestampMs - last < sampleIntervalMs) {
            return false
        }
        lastSampleTimeMs = sample.timestampMs
        _samples.add(sample)
        addCount++

        if (addCount >= downsampleThreshold) {
            downsample(sample.timestampMs)
            addCount = 0
            save()
        }
        return true
    }

    /**
     * Get samples within the specified time range, measured from the newest sample.
     */
    fun samplesForRange(range: ChartTimeRange): List<TelemetrySample> {
        if (_samples.isEmpty()) return emptyList()
        val newest = _samples.last().timestampMs
        val cutoff = newest - range.durationMs
        return _samples.filter { it.timestampMs >= cutoff }
    }

    /**
     * Compute statistics for a metric within the given time range.
     */
    fun statsForRange(metric: MetricType, range: ChartTimeRange): MetricStats {
        val rangedSamples = samplesForRange(range)
        if (rangedSamples.isEmpty()) return MetricStats(0.0, 0.0, 0.0)
        val values = rangedSamples.map { metric.extractValue(it) }
        return MetricStats(
            min = values.min(),
            max = values.max(),
            avg = values.average()
        )
    }

    /**
     * Save current history to the CSV file.
     */
    fun save() {
        val path = filePath ?: return
        val csv = TelemetryCsvSerializer.serialize(_samples)
        fileIO.writeText(path, csv)
    }

    /**
     * Clear all in-memory samples (does not delete file).
     */
    fun clear() {
        _samples.clear()
        addCount = 0
        lastSampleTimeMs = null
    }

    /**
     * Delete the history file and clear in-memory data.
     */
    fun deleteFile() {
        clear()
        filePath?.let { fileIO.delete(it) }
    }

    /**
     * Single-pass tier-based downsampling.
     * Walks from newest to oldest, enforcing minimum intervals per age tier.
     */
    internal fun downsample(nowMs: Long) {
        // Remove expired samples
        val cutoff = nowMs - maxAgeMs
        _samples.removeAll { it.timestampMs < cutoff }

        if (_samples.size <= 1) return

        // Walk from newest to oldest, tracking last-kept timestamp per tier transition
        val toRemove = mutableSetOf<Int>()
        var lastKeptTimestamp = Long.MAX_VALUE

        for (i in _samples.lastIndex downTo 0) {
            val sample = _samples[i]
            val age = nowMs - sample.timestampMs
            val minInterval = when {
                age <= TIER1_AGE_MS -> TIER1_INTERVAL_MS
                age <= TIER2_AGE_MS -> TIER2_INTERVAL_MS
                else -> TIER3_INTERVAL_MS
            }

            if (lastKeptTimestamp - sample.timestampMs < minInterval) {
                toRemove.add(i)
            } else {
                lastKeptTimestamp = sample.timestampMs
            }
        }

        // Remove marked samples in reverse order to preserve indices
        for (i in toRemove.sortedDescending()) {
            _samples.removeAt(i)
        }
    }
}
