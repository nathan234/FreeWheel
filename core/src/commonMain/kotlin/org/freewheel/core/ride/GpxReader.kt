package org.freewheel.core.ride

import kotlinx.datetime.Instant
import org.freewheel.core.utils.md5

/**
 * Lenient parser for GPX 1.1 → [RideBundle].
 *
 * Returns null on any structural failure rather than throwing — callers
 * treat null as "not a valid GPX ride" and show a user-facing error.
 *
 * Designed to cope with:
 *   - Our own exports (see [GpxWriter]).
 *   - Strava / Garmin / RideWithGPS exports (no `fw:` extensions, elevation optional).
 *   - Unknown extension namespaces alongside ours (Garmin's TrackPointExtension etc.).
 *
 * Strategy: narrow regex-based extraction instead of a full XML parser. The
 * spec is narrow enough that a parser is overkill, and KMP has no stdlib XML
 * support in commonMain. If the format ever grows beyond this, swap in
 * xmlutil or an expect/actual parser.
 */
object GpxReader {

    fun parse(input: String): RideBundle? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("<gpx") || !text.contains("</gpx>")) return null

        return try {
            val metadataBlock = extractBlock(text, "metadata") ?: ""
            val trkpts = extractTrackPoints(text)
            if (trkpts.isEmpty() && !text.contains("<trk")) return null

            val name = extractText(metadataBlock, "name")
                ?: extractText(extractBlock(text, "trk") ?: "", "name")
            val rideIdFromFile = extractFwText(metadataBlock, "rideId")
            val rideId = rideIdFromFile ?: deterministicRideId(text)

            val startedAtUtc = extractText(metadataBlock, "time")
                ?.let { parseIso8601(it) }
                ?: trkpts.firstOrNull()?.timestampMs
                ?: return null

            val manifest = RideManifest(
                rideId = rideId,
                name = name,
                startedAtUtc = startedAtUtc,
                wheelType = extractFwText(metadataBlock, "wheelType"),
                wheelName = extractFwText(metadataBlock, "wheelName"),
                wheelAddress = extractFwText(metadataBlock, "wheelAddress"),
                distanceMeters = extractFwText(metadataBlock, "distanceMeters")?.toLongOrNull(),
                durationMs = extractFwText(metadataBlock, "durationMs")?.toLongOrNull(),
                appVersion = extractFwText(metadataBlock, "appVersion"),
                schemaVersion = extractFwText(metadataBlock, "schemaVersion")?.toIntOrNull()
                    ?: RideManifest.SCHEMA_VERSION_V1,
            )

            RideBundle(manifest = manifest, samples = trkpts)
        } catch (t: Throwable) {
            null
        }
    }

    // ----- trkpt extraction -----

    private val trkptOpenRegex = Regex(
        "<trkpt\\s+[^>]*?lat=\"([^\"]+)\"[^>]*?lon=\"([^\"]+)\"[^>]*?(/>|>)"
    )

    private fun extractTrackPoints(text: String): List<RideSample> {
        val samples = mutableListOf<RideSample>()
        var cursor = 0
        while (true) {
            val match = trkptOpenRegex.find(text, cursor) ?: break
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            val selfClosing = match.groupValues[3] == "/>"

            val body: String
            val endIndex: Int
            if (selfClosing) {
                body = ""
                endIndex = match.range.last + 1
            } else {
                val closeIdx = text.indexOf("</trkpt>", startIndex = match.range.last + 1)
                if (closeIdx < 0) return emptyList() // malformed
                body = text.substring(match.range.last + 1, closeIdx)
                endIndex = closeIdx + "</trkpt>".length
            }

            if (lat != null && lon != null) {
                val ext = extractBlock(body, "extensions") ?: ""
                val timestamp = extractText(body, "time")?.let { parseIso8601(it) } ?: 0L
                samples += RideSample(
                    timestampMs = timestamp,
                    latitude = lat,
                    longitude = lon,
                    elevationMeters = extractText(body, "ele")?.toDoubleOrNull(),
                    speedKmh = extractFwText(ext, "speedKmh")?.toDoubleOrNull(),
                    batteryPct = extractFwText(ext, "batteryPct")?.toDoubleOrNull(),
                    pwmPct = extractFwText(ext, "pwmPct")?.toDoubleOrNull(),
                    powerW = extractFwText(ext, "powerW")?.toDoubleOrNull(),
                    voltageV = extractFwText(ext, "voltageV")?.toDoubleOrNull(),
                    currentA = extractFwText(ext, "currentA")?.toDoubleOrNull(),
                    motorTempC = extractFwText(ext, "motorTempC")?.toDoubleOrNull(),
                    boardTempC = extractFwText(ext, "boardTempC")?.toDoubleOrNull(),
                )
            }
            cursor = endIndex
        }
        return samples
    }

    // ----- element helpers -----

    /** Captures the inner text of the first `<tag>...</tag>` in [source]. Un-escapes basic XML entities. */
    private fun extractText(source: String, tag: String): String? {
        val pattern = Regex("<(?:[a-zA-Z][a-zA-Z0-9_]*:)?$tag(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[a-zA-Z][a-zA-Z0-9_]*:)?$tag>")
        val match = pattern.find(source) ?: return null
        return unescape(match.groupValues[1].trim()).takeIf { it.isNotEmpty() }
    }

    /** Same as [extractText] but matches only the `fw:` namespace variant. */
    private fun extractFwText(source: String, tag: String): String? {
        val pattern = Regex("<fw:$tag(?:\\s[^>]*)?>([\\s\\S]*?)</fw:$tag>")
        val match = pattern.find(source) ?: return null
        return unescape(match.groupValues[1].trim()).takeIf { it.isNotEmpty() }
    }

    /** Captures the inner body of the first `<tag>...</tag>` (including nested tags). */
    private fun extractBlock(source: String, tag: String): String? {
        val openPattern = Regex("<(?:[a-zA-Z][a-zA-Z0-9_]*:)?$tag(?:\\s[^>]*)?>")
        val open = openPattern.find(source) ?: return null
        val closeIdx = source.indexOf("</$tag>", startIndex = open.range.last + 1)
        if (closeIdx < 0) return null
        return source.substring(open.range.last + 1, closeIdx)
    }

    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    private fun parseIso8601(value: String): Long? = try {
        Instant.parse(value).toEpochMilliseconds()
    } catch (_: Throwable) {
        null
    }

    /**
     * Deterministic rideId for imports that don't carry one (Strava, Garmin, etc.).
     * MD5 of the file bytes, formatted as a UUID — re-importing the same file
     * yields the same id, so dedup works without server round-trips.
     */
    private fun deterministicRideId(text: String): String {
        val digest = md5(text.encodeToByteArray())
        val hex = digest.joinToString("") {
            val i = it.toInt() and 0xFF
            (i + 0x100).toString(16).substring(1)
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
