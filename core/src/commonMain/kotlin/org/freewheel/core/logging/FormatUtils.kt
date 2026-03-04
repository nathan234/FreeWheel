package org.freewheel.core.logging

/** Format a [Double] with a fixed number of [decimals], using '.' as decimal separator. */
internal expect fun formatFixed(value: Double, decimals: Int): String

/** Format [epochMillis] as "yyyy-MM-dd,HH:mm:ss.SSS" in the local time zone. */
internal expect fun formatTimestamp(epochMillis: Long): String
