package org.freewheel.core.diagnostics

enum class DiagLevel { INFO, WARN, ERROR }

enum class DiagCategory { RIDE, CONNECTION, RECOVERY, SYSTEM }

/**
 * One entry in the diagnostic event log.
 *
 * @param timestampMs Epoch ms (kept as Long for KMP simplicity; encoded as ISO-8601 in JSONL).
 * @param level Severity for UI filtering and rotation prioritisation.
 * @param category Top-level grouping; see [DiagCategory].
 * @param type Stable identifier (e.g. "LOG_START_OK"). Use UPPER_SNAKE_CASE; never reuse names.
 * @param sessionId Optional grouping id, e.g. UUID for one ride. Lets the UI build a timeline.
 * @param message Human-readable one-liner. Keep short — detail goes in [context].
 * @param context Arbitrary key/value detail. Insertion order is preserved.
 */
data class DiagnosticEvent(
    val timestampMs: Long,
    val level: DiagLevel,
    val category: DiagCategory,
    val type: String,
    val sessionId: String? = null,
    val message: String,
    val context: Map<String, JsonValue> = emptyMap(),
)
