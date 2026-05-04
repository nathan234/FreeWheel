package org.freewheel.core.diagnostics

import kotlinx.datetime.Instant

/**
 * Serializes [DiagnosticEvent] to the on-disk JSONL line format.
 *
 * Stable keys: ts, level, category, type, session, message, context.
 * Object keys appear in this fixed order; context keys preserve insertion order.
 *
 * Format example:
 *   {"ts":"2026-05-03T14:32:00.123Z","level":"info","category":"ride","type":"LOG_START_OK","session":"abc","message":"...","context":{...}}
 */
object DiagnosticEventEncoder {

    /** Encodes one event to a single JSONL line (without trailing newline). */
    fun encodeLine(event: DiagnosticEvent): String {
        val sb = StringBuilder(256)
        sb.append('{')

        sb.append("\"ts\":")
        encodeString(formatTimestamp(event.timestampMs), sb)

        sb.append(",\"level\":")
        encodeString(event.level.name.lowercase(), sb)

        sb.append(",\"category\":")
        encodeString(event.category.name.lowercase(), sb)

        sb.append(",\"type\":")
        encodeString(event.type, sb)

        if (event.sessionId != null) {
            sb.append(",\"session\":")
            encodeString(event.sessionId, sb)
        }

        sb.append(",\"message\":")
        encodeString(event.message, sb)

        if (event.context.isNotEmpty()) {
            sb.append(",\"context\":{")
            var first = true
            for ((k, v) in event.context) {
                if (!first) sb.append(',')
                encodeString(k, sb)
                sb.append(':')
                v.encode(sb)
                first = false
            }
            sb.append('}')
        }

        sb.append('}')
        return sb.toString()
    }

    private fun formatTimestamp(ms: Long): String =
        Instant.fromEpochMilliseconds(ms).toString()
}
