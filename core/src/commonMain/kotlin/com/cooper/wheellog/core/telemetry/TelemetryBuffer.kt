package com.cooper.wheellog.core.telemetry

import com.cooper.wheellog.core.utils.withLock

/**
 * Thread-safe ring buffer for telemetry samples.
 * Shared across Android and iOS via KMP.
 *
 * Samples are throttled to [sampleIntervalMs] and trimmed when older than [maxAgeMs].
 */
class TelemetryBuffer(
    private val sampleIntervalMs: Long = 500,
    private val maxAgeMs: Long = 60_000
) {
    private val _samples = mutableListOf<TelemetrySample>()
    private val lock = com.cooper.wheellog.core.utils.Lock()

    val samples: List<TelemetrySample> get() = lock.withLock { _samples.toList() }

    private var lastSampleTimeMs: Long? = null

    /** Dynamic max for metrics with maxValue == 0 (e.g., Power) */
    private val _dynamicMax = mutableMapOf<MetricType, Double>()

    companion object {
        /** Pre-computed set of metrics with dynamic max (maxValue == 0.0). */
        private val DYNAMIC_METRICS = MetricType.entries.filter { it.maxValue == 0.0 }
    }

    /**
     * Add a sample if enough time has elapsed since the last one.
     * Returns true if the sample was added.
     */
    fun addSampleIfNeeded(sample: TelemetrySample): Boolean = lock.withLock {
        val last = lastSampleTimeMs
        if (last != null && sample.timestampMs - last < sampleIntervalMs) {
            return@withLock false
        }
        lastSampleTimeMs = sample.timestampMs
        _samples.add(sample)

        // Trim old samples
        val cutoff = sample.timestampMs - maxAgeMs
        _samples.removeAll { it.timestampMs < cutoff }

        // Update dynamic maxes (only for metrics with maxValue == 0.0)
        for (metric in DYNAMIC_METRICS) {
            val value = kotlin.math.abs(metric.extractValue(sample))
            val current = _dynamicMax[metric] ?: 0.0
            if (value > current) {
                _dynamicMax[metric] = value
            }
        }

        true
    }

    fun clear() = lock.withLock {
        _samples.clear()
        lastSampleTimeMs = null
        _dynamicMax.clear()
    }

    /** Extract a series of values for the given metric from all buffered samples. */
    fun valuesFor(metric: MetricType): List<Double> = lock.withLock {
        _samples.map { metric.extractValue(it) }
    }

    /** Compute min/max/avg statistics for a metric across all buffered samples. */
    fun statsFor(metric: MetricType): MetricStats = lock.withLock {
        val values = _samples.map { metric.extractValue(it) }
        if (values.isEmpty()) return@withLock MetricStats(0.0, 0.0, 0.0)
        MetricStats(
            min = values.min(),
            max = values.max(),
            avg = values.average()
        )
    }

    /** Get the effective max value for a metric (static or dynamic). */
    fun effectiveMax(metric: MetricType): Double = lock.withLock {
        if (metric.maxValue > 0.0) return@withLock metric.maxValue
        // Dynamic: use tracked max, with a sensible minimum
        val tracked = _dynamicMax[metric] ?: 0.0
        if (tracked > 0.0) tracked * 1.2 else 1000.0
    }
}

data class MetricStats(
    val min: Double,
    val max: Double,
    val avg: Double
)
