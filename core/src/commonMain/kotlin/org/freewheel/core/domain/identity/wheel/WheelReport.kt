package org.freewheel.core.domain.identity.wheel

import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.domain.identity.WheelIdentity

/**
 * Pure helpers that turn a live [WheelIdentity] into a shareable wheel report —
 * either a pre-populated GitHub issue URL or plain text for the system share sheet.
 *
 * No I/O, no networking, no persistence. Submission is always user-initiated:
 * the platform layer hands the URL/text to the OS browser or share sheet, and
 * the user reviews and submits manually.
 *
 * Used to surface an "unrecognized wheel" report from the live dashboard, so the
 * catalog can be grown without a persistent encounter log.
 */
object WheelReport {

    const val GITHUB_OWNER = "nathan234"
    const val GITHUB_REPO = "FreeWheel"
    const val ISSUE_LABEL = "wheel-fingerprint"

    /**
     * Builds a `https://github.com/.../issues/new?...` URL with title/body/label
     * pre-populated. The user reviews and submits the issue in their browser.
     *
     * The [services] parameter, when non-empty, renders the wheel's full
     * GATT topology (services + characteristics) into the issue body in a
     * paste-friendly form so a maintainer can drop it straight into
     * `WheelTopologies.ALL` with minimal editing.
     */
    fun buildGitHubIssueUrl(
        identity: WheelIdentity,
        observedMaxKmh: Double = 0.0,
        appVersion: String = "",
        appPlatform: String = "",
        services: List<ServiceTopology> = emptyList(),
    ): String {
        val title = "Wheel fingerprint: ${titleFor(identity)}"
        val body = buildBody(identity, observedMaxKmh, appVersion, appPlatform, services)
        return buildString {
            append("https://github.com/")
            append(GITHUB_OWNER).append('/').append(GITHUB_REPO)
            append("/issues/new?")
            append("title=").append(percentEncode(title))
            append("&labels=").append(percentEncode(ISSUE_LABEL))
            append("&body=").append(percentEncode(body))
        }
    }

    /** Plain-text payload for the system share sheet (Telegram, email, etc.). */
    fun buildShareText(
        identity: WheelIdentity,
        observedMaxKmh: Double = 0.0,
        appVersion: String = "",
        appPlatform: String = "",
        services: List<ServiceTopology> = emptyList(),
    ): String =
        "Wheel fingerprint: ${titleFor(identity)}\n\n" +
            buildBody(identity, observedMaxKmh, appVersion, appPlatform, services)

    private fun titleFor(identity: WheelIdentity): String {
        val parts = listOf(
            identity.wheelType.name,
            identity.btName.ifEmpty { identity.model }.ifEmpty { identity.version }
        )
        return parts.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "unknown" }
    }

    private fun buildBody(
        identity: WheelIdentity,
        observedMaxKmh: Double,
        appVersion: String,
        appPlatform: String,
        services: List<ServiceTopology>,
    ): String = buildString {
        appendField("Wheel type", identity.wheelType.name)
        appendField("Advertised BLE name", identity.btName)
        appendField("Decoded model", identity.model)
        appendField("Firmware version", identity.version)
        appendField("Brand override", identity.brand)
        appendField("Decoder name", identity.name)
        appendField("Mode", identity.modeStr)
        appendField("Serial number", identity.serialNumber)
        appendField("Observed peak speed (km/h)", observedMaxKmh.takeIf { it > 0.0 }?.toString().orEmpty())
        appendField("App version", appVersion)
        appendField("App platform", appPlatform)
        appendTopologySection(services)
    }

    private fun StringBuilder.appendField(label: String, value: String) {
        if (value.isEmpty()) return
        append("**").append(label).append("**: ").append(value).append('\n')
    }

    /**
     * Render the full GATT topology in a paste-friendly form so a
     * maintainer can drop it into `WheelTopologies.ALL`. Per-service
     * line is `- {service-uuid}: [{c1}, {c2}, ...]`. Services render in
     * input list order; characteristics render in the order returned by
     * the [ServiceTopology] set's iterator (insertion order under
     * `setOf(...)`'s LinkedHashSet). An empty topology omits the entire
     * section so legacy callers that haven't wired up topology data yet
     * don't bloat their issue body.
     */
    private fun StringBuilder.appendTopologySection(services: List<ServiceTopology>) {
        if (services.isEmpty()) return
        val count = services.size
        val noun = if (count == 1) "service" else "services"
        append("**Service topology** (").append(count).append(' ').append(noun).append("):\n")
        for (svc in services) {
            append("- ").append(svc.uuid).append(": [")
            append(svc.characteristics.joinToString(", "))
            append("]\n")
        }
    }

    /**
     * RFC 3986 percent-encoding for URL query strings. Unreserved characters
     * (A-Z a-z 0-9 - _ . ~) pass through; everything else is %XX-encoded byte-wise (UTF-8).
     */
    internal fun percentEncode(input: String): String {
        val out = StringBuilder(input.length)
        for (byte in input.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            val safe = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '-' || c == '_' || c == '.' || c == '~'
            if (safe) {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[b ushr 4])
                out.append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
