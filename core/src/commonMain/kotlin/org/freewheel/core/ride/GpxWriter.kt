package org.freewheel.core.ride

import kotlinx.datetime.Instant

/**
 * Streaming XML writer for [RideBundle] → GPX 1.1 with FreeWheel extensions.
 *
 * Design: append to a StringBuilder, no DOM, no external XML lib. The format
 * is narrow enough that a DOM would be over-engineering and would cost a
 * platform dependency we don't otherwise need in commonMain.
 *
 * See `docs/ghost-routes-plan.md` for the format spec.
 */
object GpxWriter {

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val FW_NS = "https://freewheel.app/gpx/v1"

    fun write(bundle: RideBundle): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"FreeWheel\"\n")
        out.append("     xmlns=\"").append(GPX_NS).append("\"\n")
        out.append("     xmlns:fw=\"").append(FW_NS).append("\">\n")

        writeMetadata(out, bundle.manifest)
        writeTrack(out, bundle)

        out.append("</gpx>\n")
        return out.toString()
    }

    private fun writeMetadata(out: StringBuilder, m: RideManifest) {
        out.append("  <metadata>\n")
        m.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
        out.append("    <time>").append(iso8601(m.startedAtUtc)).append("</time>\n")
        out.append("    <fw:rideId>").append(escape(m.rideId)).append("</fw:rideId>\n")
        m.wheelType?.let { out.append("    <fw:wheelType>").append(escape(it)).append("</fw:wheelType>\n") }
        m.wheelName?.let { out.append("    <fw:wheelName>").append(escape(it)).append("</fw:wheelName>\n") }
        m.wheelAddress?.let { out.append("    <fw:wheelAddress>").append(escape(it)).append("</fw:wheelAddress>\n") }
        m.distanceMeters?.let { out.append("    <fw:distanceMeters>").append(it).append("</fw:distanceMeters>\n") }
        m.durationMs?.let { out.append("    <fw:durationMs>").append(it).append("</fw:durationMs>\n") }
        m.appVersion?.let { out.append("    <fw:appVersion>").append(escape(it)).append("</fw:appVersion>\n") }
        out.append("    <fw:schemaVersion>").append(m.schemaVersion).append("</fw:schemaVersion>\n")
        out.append("  </metadata>\n")
    }

    private fun writeTrack(out: StringBuilder, bundle: RideBundle) {
        out.append("  <trk>\n")
        bundle.manifest.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
        out.append("    <trkseg>\n")
        for (s in bundle.samples) writeSample(out, s)
        out.append("    </trkseg>\n")
        out.append("  </trk>\n")
    }

    private fun writeSample(out: StringBuilder, s: RideSample) {
        out.append("      <trkpt lat=\"").append(s.latitude)
            .append("\" lon=\"").append(s.longitude).append("\">\n")
        s.elevationMeters?.let { out.append("        <ele>").append(it).append("</ele>\n") }
        out.append("        <time>").append(iso8601(s.timestampMs)).append("</time>\n")

        if (hasAnyExtensions(s)) {
            out.append("        <extensions>\n")
            s.speedKmh?.let { out.append("          <fw:speedKmh>").append(it).append("</fw:speedKmh>\n") }
            s.batteryPct?.let { out.append("          <fw:batteryPct>").append(it).append("</fw:batteryPct>\n") }
            s.pwmPct?.let { out.append("          <fw:pwmPct>").append(it).append("</fw:pwmPct>\n") }
            s.powerW?.let { out.append("          <fw:powerW>").append(it).append("</fw:powerW>\n") }
            s.voltageV?.let { out.append("          <fw:voltageV>").append(it).append("</fw:voltageV>\n") }
            s.currentA?.let { out.append("          <fw:currentA>").append(it).append("</fw:currentA>\n") }
            s.motorTempC?.let { out.append("          <fw:motorTempC>").append(it).append("</fw:motorTempC>\n") }
            s.boardTempC?.let { out.append("          <fw:boardTempC>").append(it).append("</fw:boardTempC>\n") }
            out.append("        </extensions>\n")
        }

        out.append("      </trkpt>\n")
    }

    private fun hasAnyExtensions(s: RideSample): Boolean =
        s.speedKmh != null || s.batteryPct != null || s.pwmPct != null || s.powerW != null ||
            s.voltageV != null || s.currentA != null || s.motorTempC != null || s.boardTempC != null

    private fun iso8601(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()

    private fun escape(value: String): String {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            else -> out.append(c)
        }
        return out.toString()
    }
}
