package org.freewheel.core.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun formatFixed(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

internal actual fun formatTimestamp(epochMillis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", Locale.US)
    return sdf.format(Date(epochMillis))
}
