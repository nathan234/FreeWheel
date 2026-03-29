package org.freewheel.core.logging

import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.utils.Logger

/**
 * Cross-platform ride logger that writes CSV files matching the legacy WheelLog format.
 *
 * Usage:
 * 1. Call [start] with a file path and GPS flag.
 * 2. Call [writeSample] on every telemetry update (internally throttled to 1Hz).
 * 3. Call [stop] to close the file and get [RideMetadata].
 *
 * Thread safety: callers must ensure [writeSample] and [stop] are not called concurrently.
 */
class RideLogger(private val fileWriter: FileWriter = FileWriter()) {

    private var isActive = false
    private var formatter: CsvFormatter? = null
    private var fileName = ""

    // Throttle state
    private var lastWriteTimeMs = 0L

    // Metadata tracking
    private var startTimeMs = 0L
    private var startTotalDistance = 0L
    private var maxSpeedKmh = 0.0
    private var totalSpeedKmh = 0.0
    private var sampleCount = 0
    private var maxCurrentA = 0.0
    private var maxPowerW = 0.0
    private var maxPwmPercent = 0.0
    private var totalPowerW = 0.0

    val isLogging: Boolean get() = isActive

    /**
     * Returns a snapshot of live ride stats, or null if not recording.
     */
    fun liveStats(currentTimeMs: Long, currentTotalDistance: Long): LiveRideStats? {
        if (!isActive) return null
        val distM = if (startTotalDistance > 0 && currentTotalDistance > startTotalDistance) {
            currentTotalDistance - startTotalDistance
        } else 0L
        return LiveRideStats(
            startTimeMs = startTimeMs,
            elapsedMs = currentTimeMs - startTimeMs,
            maxSpeedKmh = maxSpeedKmh,
            distanceMeters = distM
        )
    }

    /**
     * Start a new ride recording.
     *
     * @param filePath Full path to the CSV file to create.
     * @param withGps true to include GPS columns in the header.
     * @param currentTimeMs Current epoch time in milliseconds (for testability).
     * @return true if the file was created successfully.
     */
    fun start(filePath: String, withGps: Boolean, currentTimeMs: Long): Boolean {
        if (isActive) return false

        if (!fileWriter.open(filePath)) return false

        val fmt = CsvFormatter.create(withGps)
        formatter = fmt
        fileName = filePath.substringAfterLast('/')

        fileWriter.writeLine(fmt.header)

        startTimeMs = currentTimeMs
        lastWriteTimeMs = 0L
        startTotalDistance = 0L
        maxSpeedKmh = 0.0
        totalSpeedKmh = 0.0
        sampleCount = 0
        maxCurrentA = 0.0
        maxPowerW = 0.0
        maxPwmPercent = 0.0
        totalPowerW = 0.0
        isActive = true
        return true
    }

    /**
     * Write a telemetry sample if at least 1 second has elapsed since the last write.
     *
     * @param telemetry Current telemetry state.
     * @param modeStr Ride mode string (from WheelIdentity).
     * @param gps Optional GPS location (ignored if [start] was called with withGps=false).
     * @param currentTimeMs Current epoch time in milliseconds.
     */
    fun writeSample(telemetry: TelemetryState, modeStr: String = "", gps: GpsLocation?, currentTimeMs: Long) {
        if (!isActive) return

        // 1Hz throttle
        if (currentTimeMs - lastWriteTimeMs < 1000L) return
        lastWriteTimeMs = currentTimeMs

        // Track metadata
        if (sampleCount == 0) {
            startTotalDistance = telemetry.totalDistance
        }
        val speedKmh = telemetry.speedKmh
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
        totalSpeedKmh += speedKmh

        val currentA = kotlin.math.abs(telemetry.currentA)
        if (currentA > maxCurrentA) maxCurrentA = currentA
        val powerW = kotlin.math.abs(telemetry.powerW)
        if (powerW > maxPowerW) maxPowerW = powerW
        val pwm = telemetry.pwmPercent
        if (pwm > maxPwmPercent) maxPwmPercent = pwm
        totalPowerW += powerW

        sampleCount++

        val fmt = formatter ?: return

        val dateTime = formatTimestamp(currentTimeMs)
        val tripDistance = (telemetry.totalDistance - startTotalDistance).toInt()
        val row = fmt.row(dateTime, telemetry, modeStr, tripDistance, gps)
        try {
            fileWriter.writeLine(row)
        } catch (e: Exception) {
            Logger.e("RideLogger", "Failed to write sample", e)
        }
    }

    /**
     * Stop recording and close the file.
     *
     * @param currentTimeMs Current epoch time in milliseconds.
     * @return [RideMetadata] for the completed ride, or null if not logging.
     */
    fun stop(currentTimeMs: Long, lastTotalDistance: Long = 0): RideMetadata? {
        if (!isActive) return null

        fileWriter.close()
        isActive = false
        formatter = null

        val endTimeMs = currentTimeMs
        val durationSec = (endTimeMs - startTimeMs) / 1000L
        val avgSpeed = if (sampleCount > 0) totalSpeedKmh / sampleCount else 0.0

        // Distance: use lastTotalDistance if provided, otherwise compute from tracked start
        val distanceM = if (lastTotalDistance > 0 && startTotalDistance > 0) {
            lastTotalDistance - startTotalDistance
        } else {
            0L
        }

        // Energy consumption
        val avgPowerW = if (sampleCount > 0) totalPowerW / sampleCount else 0.0
        val consumptionWh = if (durationSec > 0) avgPowerW * durationSec / 3600.0 else 0.0
        val consumptionWhPerKm = if (distanceM > 0) consumptionWh * 1000.0 / distanceM else 0.0

        return RideMetadata(
            fileName = fileName,
            startTimeMillis = startTimeMs,
            endTimeMillis = endTimeMs,
            durationSeconds = durationSec,
            distanceMeters = distanceM,
            maxSpeedKmh = maxSpeedKmh,
            avgSpeedKmh = avgSpeed,
            sampleCount = sampleCount,
            maxCurrentA = maxCurrentA,
            maxPowerW = maxPowerW,
            maxPwmPercent = maxPwmPercent,
            consumptionWh = consumptionWh,
            consumptionWhPerKm = consumptionWhPerKm
        )
    }
}
