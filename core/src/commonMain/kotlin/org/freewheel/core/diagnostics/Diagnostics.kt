package org.freewheel.core.diagnostics

import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.currentTimeMillis

/**
 * Top-level facade for emitting diagnostic events. All call sites in the
 * codebase should go through here, not [DiagnosticLogStore] directly, so
 * encoding stays consistent and callers don't need to think about the
 * persistence layer.
 *
 * Initialise once at app startup with [init]. Events emitted before init are
 * dropped silently (rather than buffered) — keep init early.
 */
object Diagnostics {

    private var store: DiagnosticLogStore? = null

    fun init(store: DiagnosticLogStore) {
        this.store = store
    }

    /**
     * Records one event. Safe to call from any thread; the underlying store
     * serialises writes. Failures are logged via [Logger] but never thrown.
     */
    fun log(event: DiagnosticEvent) {
        val s = store ?: return
        try {
            val line = DiagnosticEventEncoder.encodeLine(event)
            s.append(line)
        } catch (e: Throwable) {
            Logger.e("Diagnostics", "Failed to append event: ${event.type}", e)
        }
    }

    fun readRecent(maxLines: Int = 500): List<String> =
        store?.readRecent(maxLines) ?: emptyList()

    fun activeFilePath(): String? = store?.activeFilePath()

    fun clear() {
        store?.clear()
    }

    // ---- Convenience emitters ---------------------------------------------
    //
    // Each method builds a typed event and forwards to [log]. Keeps emission
    // sites tiny and means event names live in one place.

    fun rideStartRequested(sessionId: String) =
        log(event(DiagLevel.INFO, DiagCategory.RIDE, "LOG_START_REQUESTED", sessionId,
            message = "Logging start requested"))

    fun rideStartOk(sessionId: String, fileName: String, gpsEnabled: Boolean, wheelType: String?) =
        log(event(DiagLevel.INFO, DiagCategory.RIDE, "LOG_START_OK", sessionId,
            message = "Started ride $fileName (gps=$gpsEnabled, wheel=${wheelType ?: "?"})",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "gpsEnabled" to JsonValue.of(gpsEnabled),
                "wheelType" to JsonValue.of(wheelType),
            )))

    fun rideStartFailed(sessionId: String, fileName: String, reason: String) =
        log(event(DiagLevel.ERROR, DiagCategory.RIDE, "LOG_START_FAILED", sessionId,
            message = "Failed to start ride $fileName: $reason",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "reason" to JsonValue.of(reason),
            )))

    fun rideSampleWriteFailed(sessionId: String, reason: String) =
        log(event(DiagLevel.ERROR, DiagCategory.RIDE, "LOG_SAMPLE_WRITE_FAILED", sessionId,
            message = "Sample write failed: $reason",
            context = ctx("reason" to JsonValue.of(reason))))

    fun ridePause(sessionId: String, reason: String) =
        log(event(DiagLevel.WARN, DiagCategory.RIDE, "LOG_PAUSE", sessionId,
            message = "Ride paused ($reason)",
            context = ctx("reason" to JsonValue.of(reason))))

    fun rideResume(sessionId: String, pausedMs: Long) =
        log(event(DiagLevel.INFO, DiagCategory.RIDE, "LOG_RESUME", sessionId,
            message = "Ride resumed after ${pausedMs}ms",
            context = ctx("pausedMs" to JsonValue.of(pausedMs))))

    fun rideStopRequested(sessionId: String) =
        log(event(DiagLevel.INFO, DiagCategory.RIDE, "LOG_STOP_REQUESTED", sessionId,
            message = "Logging stop requested"))

    fun rideStopOk(
        sessionId: String,
        fileName: String,
        sampleCount: Int,
        durationSec: Long,
        distanceM: Long,
    ) = log(event(DiagLevel.INFO, DiagCategory.RIDE, "LOG_STOP_OK", sessionId,
        message = "Stopped ride $fileName ($sampleCount samples, ${durationSec}s, ${distanceM}m)",
        context = ctx(
            "fileName" to JsonValue.of(fileName),
            "sampleCount" to JsonValue.of(sampleCount),
            "durationSec" to JsonValue.of(durationSec),
            "distanceM" to JsonValue.of(distanceM),
        )))

    fun rideStopNoMetadata(sessionId: String) =
        log(event(DiagLevel.ERROR, DiagCategory.RIDE, "LOG_STOP_NO_METADATA", sessionId,
            message = "Stop returned null metadata — index not updated"))

    fun recoveryPassStart() =
        log(event(DiagLevel.INFO, DiagCategory.RECOVERY, "RECOVERY_PASS_START", null,
            message = "Reconcile pass starting"))

    fun recoveryPassEnd(
        recovered: Int,
        phantom: Int,
        skipped: Int,
        corrupt: Int,
        elapsedMs: Long,
    ) = log(event(
        if (recovered > 0 || phantom > 0 || corrupt > 0) DiagLevel.WARN else DiagLevel.INFO,
        DiagCategory.RECOVERY, "RECOVERY_PASS_END", null,
        message = "Reconcile done: recovered=$recovered phantom=$phantom skipped=$skipped corrupt=$corrupt (${elapsedMs}ms)",
        context = ctx(
            "recovered" to JsonValue.of(recovered),
            "phantom" to JsonValue.of(phantom),
            "skipped" to JsonValue.of(skipped),
            "corrupt" to JsonValue.of(corrupt),
            "elapsedMs" to JsonValue.of(elapsedMs),
        )))

    fun recovered(fileName: String, sampleCount: Int, durationSec: Long, distanceM: Long) =
        log(event(DiagLevel.WARN, DiagCategory.RECOVERY, "RECOVERED", null,
            message = "Recovered orphan $fileName ($sampleCount samples, ${durationSec}s)",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "sampleCount" to JsonValue.of(sampleCount),
                "durationSec" to JsonValue.of(durationSec),
                "distanceM" to JsonValue.of(distanceM),
            )))

    fun phantom(fileName: String, reason: String) =
        log(event(DiagLevel.WARN, DiagCategory.RECOVERY, "PHANTOM", null,
            message = "Phantom index entry $fileName ($reason) — dropped",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "reason" to JsonValue.of(reason),
            )))

    fun skipped(fileName: String, reason: String, sampleCount: Int, durationSec: Long) =
        log(event(DiagLevel.INFO, DiagCategory.RECOVERY, "SKIPPED", null,
            message = "Skipped $fileName: $reason ($sampleCount samples, ${durationSec}s)",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "reason" to JsonValue.of(reason),
                "sampleCount" to JsonValue.of(sampleCount),
                "durationSec" to JsonValue.of(durationSec),
            )))

    fun corrupt(fileName: String, reason: String) =
        log(event(DiagLevel.ERROR, DiagCategory.RECOVERY, "CORRUPT", null,
            message = "Corrupt CSV $fileName: $reason",
            context = ctx(
                "fileName" to JsonValue.of(fileName),
                "reason" to JsonValue.of(reason),
            )))

    fun snapshot(
        ridesOnDisk: Int,
        indexEntries: Int,
        phantoms: Int,
        orphansAtBoot: Int,
        isLogging: Boolean,
        currentlyConnected: Boolean,
        lastWheelType: String?,
        lastWheelMacRedacted: String?,
        currentLogFileBytes: Long,
    ) = log(event(DiagLevel.INFO, DiagCategory.SYSTEM, "DIAGNOSTIC_SNAPSHOT", null,
        message = "Snapshot: rides=$ridesOnDisk index=$indexEntries phantoms=$phantoms orphans=$orphansAtBoot logging=$isLogging connected=$currentlyConnected",
        context = ctx(
            "ridesOnDisk" to JsonValue.of(ridesOnDisk),
            "indexEntries" to JsonValue.of(indexEntries),
            "phantoms" to JsonValue.of(phantoms),
            "orphansAtBoot" to JsonValue.of(orphansAtBoot),
            "isLogging" to JsonValue.of(isLogging),
            "currentlyConnected" to JsonValue.of(currentlyConnected),
            "lastWheelType" to JsonValue.of(lastWheelType),
            "lastWheelMac" to JsonValue.of(lastWheelMacRedacted),
            "currentLogFileBytes" to JsonValue.of(currentLogFileBytes),
        )))

    // ---- Internal builders -------------------------------------------------

    private fun event(
        level: DiagLevel,
        category: DiagCategory,
        type: String,
        sessionId: String?,
        message: String,
        context: Map<String, JsonValue> = emptyMap(),
    ) = DiagnosticEvent(
        timestampMs = currentTimeMillis(),
        level = level,
        category = category,
        type = type,
        sessionId = sessionId,
        message = message,
        context = context,
    )

    private fun ctx(vararg pairs: Pair<String, JsonValue>): Map<String, JsonValue> =
        LinkedHashMap<String, JsonValue>(pairs.size).apply {
            for ((k, v) in pairs) put(k, v)
        }
}
