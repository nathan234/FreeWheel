package org.freewheel.core.replay

import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.utils.ByteUtils

data class CaptureHeader(
    val wheelType: WheelType,
    val wheelTypeName: String,
    val wheelName: String,
    val firmware: String,
    val appVersion: String
)

data class CapturedPacket(
    val timestampMs: Long,
    val direction: BlePacketDirection,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPacket) return false
        return timestampMs == other.timestampMs &&
            direction == other.direction &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class CapturedMarker(
    val timestampMs: Long,
    val label: String
)

sealed class CaptureEntry {
    abstract val timestampMs: Long

    data class Packet(val packet: CapturedPacket) : CaptureEntry() {
        override val timestampMs: Long get() = packet.timestampMs
    }

    data class Marker(val marker: CapturedMarker) : CaptureEntry() {
        override val timestampMs: Long get() = marker.timestampMs
    }
}

data class CaptureFile(
    val header: CaptureHeader,
    val entries: List<CaptureEntry>,
    val durationMs: Long
)

/**
 * Parses CSV capture files produced by [BleCaptureLogger] into structured data.
 */
class BleCaptureReader {

    /**
     * Parse a complete capture CSV file.
     * Returns null if the header is missing or malformed.
     */
    fun parse(csvContent: String): CaptureFile? {
        val header = parseHeader(csvContent) ?: return null
        val entries = mutableListOf<CaptureEntry>()

        for (line in csvContent.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            if (line == CSV_HEADER) continue

            val entry = parseLine(line) ?: continue
            entries.add(entry)
        }

        val durationMs = if (entries.size >= 2) {
            entries.last().timestampMs - entries.first().timestampMs
        } else {
            0L
        }

        return CaptureFile(header = header, entries = entries, durationMs = durationMs)
    }

    /**
     * Parse only the header metadata from a capture CSV.
     * Lightweight — doesn't parse data rows. Useful for file listings.
     */
    fun parseHeader(csvContent: String): CaptureHeader? {
        var wheelTypeName: String? = null
        var wheelName: String? = null
        var firmware: String? = null
        var appVersion: String? = null

        for (line in csvContent.lines()) {
            if (!line.startsWith("#")) {
                // Past the header comments — stop looking
                if (line == CSV_HEADER || line.isBlank()) continue
                break
            }
            val content = line.removePrefix("#").trim()
            when {
                content.startsWith("wheel_type:") ->
                    wheelTypeName = content.removePrefix("wheel_type:").trim()
                content.startsWith("wheel_name:") ->
                    wheelName = content.removePrefix("wheel_name:").trim()
                content.startsWith("firmware:") ->
                    firmware = content.removePrefix("firmware:").trim()
                content.startsWith("app_version:") ->
                    appVersion = content.removePrefix("app_version:").trim()
            }
        }

        val typeName = wheelTypeName ?: return null
        return CaptureHeader(
            wheelType = WheelType.fromString(typeName),
            wheelTypeName = typeName,
            wheelName = wheelName ?: "",
            firmware = firmware ?: "",
            appVersion = appVersion ?: ""
        )
    }

    private fun parseLine(line: String): CaptureEntry? {
        // CSV format: timestamp_ms,direction,length,hex_data,marker
        val parts = line.split(",", limit = 5)
        if (parts.size < 5) return null

        val timestampMs = parts[0].toLongOrNull() ?: return null
        val directionStr = parts[1].trim()
        val hexData = parts[3].trim()
        val markerLabel = parts[4].trim()

        // Marker row: direction is empty, marker label is present
        if (directionStr.isEmpty() && markerLabel.isNotEmpty()) {
            return CaptureEntry.Marker(CapturedMarker(timestampMs, markerLabel))
        }

        // Packet row: direction is RX or TX
        if (directionStr.isEmpty()) return null
        val direction = try {
            BlePacketDirection.valueOf(directionStr)
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (hexData.isEmpty()) return null
        val data = try {
            ByteUtils.hexToBytes(hexData)
        } catch (_: Exception) {
            return null
        }

        return CaptureEntry.Packet(CapturedPacket(timestampMs, direction, data))
    }

    companion object {
        private const val CSV_HEADER = "timestamp_ms,direction,length,hex_data,marker"
    }
}
