package com.cooper.wheellog.core.utils

/**
 * Platform-specific date formatting.
 * Uses java.util.Calendar on Android, Foundation.Calendar on iOS.
 */
expect object PlatformDateFormatter {
    /**
     * Format a timestamp as a friendly date string.
     * Returns "Today, 3:15 PM", "Yesterday, 3:15 PM", "Mon, Jan 5, 3:15 PM",
     * or "Jan 5, 2024, 3:15 PM" for dates in a different year.
     */
    fun formatFriendlyDate(epochMs: Long): String

    /**
     * Format a timestamp as a ride log filename (without extension).
     * Uses format "yyyy_MM_dd_HH_mm_ss".
     */
    fun formatRideFilename(epochMs: Long): String
}
