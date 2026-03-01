package com.cooper.wheellog.core.utils

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual object PlatformDateFormatter {
    actual fun formatFriendlyDate(epochMs: Long): String {
        if (epochMs <= 0L) return "Unknown date"

        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        val now = NSDate()
        val calendar = NSCalendar.currentCalendar

        val timeFormatter = NSDateFormatter()
        timeFormatter.dateFormat = "h:mm a"
        val timeStr = timeFormatter.stringFromDate(date)

        val dateComponents = calendar.components(
            NSCalendarUnitYear or NSCalendarUnitDay,
            fromDate = date
        )
        val nowComponents = calendar.components(
            NSCalendarUnitYear or NSCalendarUnitDay,
            fromDate = now
        )

        return when {
            calendar.isDateInToday(date) -> "Today, $timeStr"
            calendar.isDateInYesterday(date) -> "Yesterday, $timeStr"
            dateComponents.year == nowComponents.year -> {
                val dayFormatter = NSDateFormatter()
                dayFormatter.dateFormat = "EEE, MMM d"
                "${dayFormatter.stringFromDate(date)}, $timeStr"
            }
            else -> {
                val dayFormatter = NSDateFormatter()
                dayFormatter.dateFormat = "MMM d, yyyy"
                "${dayFormatter.stringFromDate(date)}, $timeStr"
            }
        }
    }

    actual fun formatRideFilename(epochMs: Long): String {
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        val formatter = NSDateFormatter()
        formatter.dateFormat = "yyyy_MM_dd_HH_mm_ss"
        return formatter.stringFromDate(date)
    }
}
