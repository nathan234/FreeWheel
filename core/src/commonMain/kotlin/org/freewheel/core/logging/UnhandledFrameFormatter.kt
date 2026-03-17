package org.freewheel.core.logging

import org.freewheel.core.utils.PlatformDateFormatter

/**
 * Formats [UnhandledFrameEntry] lists as CSV for sharing.
 *
 * Follows the same metadata-header pattern as [BleCaptureLogger]:
 * `#`-prefixed comment lines for context, then a CSV header and data rows.
 *
 * Accessible from Swift as `UnhandledFrameFormatter.shared`.
 */
object UnhandledFrameFormatter {

    private const val CSV_HEADER = "count,first_seen,last_seen,length,reason,hex_data"

    /**
     * Format unhandled frame entries as CSV with metadata headers.
     *
     * @param entries Frames to include.
     * @param wheelType Wheel type name (e.g., "INMOTION_V2").
     * @param model Detected model (e.g., "V14").
     * @param firmware Firmware version string.
     * @param platform "android" or "ios".
     * @return CSV text, or null if [entries] is empty.
     */
    fun format(
        entries: List<UnhandledFrameEntry>,
        wheelType: String,
        model: String,
        firmware: String,
        platform: String
    ): String? {
        if (entries.isEmpty()) return null

        return buildString {
            // Metadata headers (same pattern as BleCaptureLogger)
            appendLine("# FreeWheel Unhandled Frames")
            appendLine("# wheel_type: $wheelType")
            if (model.isNotEmpty()) appendLine("# model: $model")
            if (firmware.isNotEmpty()) appendLine("# firmware: $firmware")
            appendLine("# platform: $platform")
            appendLine("# unique_frames: ${entries.size}")
            appendLine("# total_occurrences: ${entries.sumOf { it.count }}")
            appendLine(CSV_HEADER)

            for (entry in entries) {
                val firstSeen = PlatformDateFormatter.formatFriendlyDate(entry.firstSeenMs)
                val lastSeen = PlatformDateFormatter.formatFriendlyDate(entry.lastSeenMs)
                // Escape reason (replace commas) to keep CSV valid
                val safeReason = entry.reason.replace(",", ";")
                appendLine("${entry.count},$firstSeen,$lastSeen,${entry.frameSize},$safeReason,${entry.frameHex}")
            }
        }.trimEnd('\n')
    }
}
