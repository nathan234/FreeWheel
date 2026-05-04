package org.freewheel.core.diagnostics

/**
 * Returns a MAC address formatted for inclusion in diagnostic events.
 * Honors the [Diagnostics.redactMacAddresses] toggle: when enabled (default),
 * delegates to [redactMac]; when disabled (debug builds, opt-in), returns
 * the raw value.
 */
fun formatMacForDiagnostics(mac: String?): String? =
    if (Diagnostics.redactMacAddresses) redactMac(mac) else mac

/**
 * Pure redaction: keeps OUI (vendor) and last byte, masks the device-specific
 * middle bytes. Use this directly only when bypassing the global toggle.
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
