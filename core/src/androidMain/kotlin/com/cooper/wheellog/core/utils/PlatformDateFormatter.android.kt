package com.cooper.wheellog.core.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

actual object PlatformDateFormatter {
    actual fun formatFriendlyDate(epochMs: Long): String {
        if (epochMs <= 0) return "Unknown date"

        val date = Date(epochMs)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeStr = timeFormat.format(date)

        return when {
            isSameDay(now, then) -> "Today, $timeStr"
            isYesterday(now, then) -> "Yesterday, $timeStr"
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                "${dayFormat.format(date)}, $timeStr"
            }
            else -> {
                val dayFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                "${dayFormat.format(date)}, $timeStr"
            }
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, then: Calendar): Boolean {
        val yesterday = now.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(yesterday, then)
    }

    actual fun formatRideFilename(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        return sdf.format(Date(epochMs))
    }
}
