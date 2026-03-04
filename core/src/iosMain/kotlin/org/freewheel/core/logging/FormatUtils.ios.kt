@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.freewheel.core.logging

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.stringWithFormat

internal actual fun formatFixed(value: Double, decimals: Int): String =
    NSString.stringWithFormat("%.${decimals}f", value)

internal actual fun formatTimestamp(epochMillis: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd,HH:mm:ss.SSS"
    formatter.locale = NSLocale("en_US_POSIX")
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    return formatter.stringFromDate(date)
}
