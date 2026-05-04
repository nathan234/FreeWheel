package org.freewheel.core.diagnostics

/**
 * Returns a partially-redacted MAC address suitable for diagnostic logs.
 * Keeps OUI (vendor) and last byte, masks the device-specific middle bytes.
 *
 * Examples:
 *   "AA:BB:CC:DD:EE:FF" → "AA:BB:CC:**:**:FF"
 *   "aa-bb-cc-dd-ee-ff" → "aa-bb-cc-**-**-ff" (separator preserved)
 *   "AABBCCDDEEFF"      → "AABBCC****FF"
 *   ""                  → ""
 *   null                → null
 */
fun redactMac(mac: String?): String? {
    if (mac == null) return null
    if (mac.isEmpty()) return mac

    // Detect separator (':' or '-') if any
    val sep = mac.firstOrNull { it == ':' || it == '-' }
    return if (sep != null) {
        val parts = mac.split(sep)
        if (parts.size != 6) return mac // unexpected format — leave alone
        listOf(parts[0], parts[1], parts[2], "**", "**", parts[5]).joinToString(sep.toString())
    } else {
        if (mac.length != 12) return mac
        mac.substring(0, 6) + "****" + mac.substring(10, 12)
    }
}
