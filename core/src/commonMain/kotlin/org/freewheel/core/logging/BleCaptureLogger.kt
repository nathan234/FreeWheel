package org.freewheel.core.logging

import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock

enum class BlePacketDirection { RX, TX }

data class BleCaptureMetadata(
    val fileName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long,
    val packetCount: Int,
    val rxPacketCount: Int,
    val txPacketCount: Int,
    val markerCount: Int,
    val wheelTypeName: String,
    val wheelName: String
)

/**
 * Cross-platform BLE packet capture logger that writes CSV files.
 *
 * Produces a CSV with metadata header comments and one row per packet or marker.
 * Every packet is logged (no throttling) — designed for protocol debugging.
 *
 * Thread safety: all public methods are Lock-protected (BLE + UI threads).
 */
class BleCaptureLogger(private val fileWriter: FileWriter = FileWriter()) {

    private val lock = Lock()

    private var active = false
    private var fileName = ""
    private var startTimeMs = 0L
    private var packetCount = 0
    private var rxCount = 0
    private var txCount = 0
    private var markerCount = 0
    private var wheelTypeName = ""
    private var wheelName = ""

    val isCapturing: Boolean get() = lock.withLock { active }

    /**
     * Start a new BLE capture.
     *
     * @param filePath Full path to the CSV file to create.
     * @param wheelTypeName Wheel type (e.g., "INMOTION_V2").
     * @param wheelName Wheel display name (e.g., "P6").
     * @param firmware Firmware version string.
     * @param appVersion App version string.
     * @param currentTimeMs Current epoch time in milliseconds.
     * @return true if the file was created successfully.
     */
    fun start(
        filePath: String,
        wheelTypeName: String,
        wheelName: String,
        firmware: String,
        appVersion: String,
        currentTimeMs: Long
    ): Boolean = lock.withLock {
        if (active) return@withLock false

        if (!fileWriter.open(filePath)) return@withLock false

        this.fileName = filePath.substringAfterLast('/')
        this.startTimeMs = currentTimeMs
        this.wheelTypeName = wheelTypeName
        this.wheelName = wheelName
        this.packetCount = 0
        this.rxCount = 0
        this.txCount = 0
        this.markerCount = 0

        // Write metadata header
        val captureStart = formatCaptureTimestamp(currentTimeMs)
        fileWriter.writeLine("# FreeWheel BLE Capture")
        fileWriter.writeLine("# wheel_type: $wheelTypeName")
        fileWriter.writeLine("# wheel_name: $wheelName")
        fileWriter.writeLine("# firmware: $firmware")
        fileWriter.writeLine("# capture_start: $captureStart")
        fileWriter.writeLine("# app_version: $appVersion")
        fileWriter.writeLine(CSV_HEADER)

        active = true
        true
    }

    /**
     * Log a BLE packet.
     *
     * @param data Raw packet bytes.
     * @param direction RX (received from wheel) or TX (sent to wheel).
     * @param currentTimeMs Current epoch time in milliseconds.
     */
    fun logPacket(data: ByteArray, direction: BlePacketDirection, currentTimeMs: Long) {
        lock.withLock {
            if (!active) return

            val hex = ByteUtils.bytesToHex(data)
            try {
                fileWriter.writeLine("$currentTimeMs,${direction.name},${data.size},$hex,")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to write packet", e)
                return
            }
            packetCount++
            when (direction) {
                BlePacketDirection.RX -> rxCount++
                BlePacketDirection.TX -> txCount++
            }
        }
    }

    /**
     * Insert a user marker row into the capture.
     * Marker labels are escaped: commas and newlines are replaced.
     *
     * @param label Marker label text.
     * @param currentTimeMs Current epoch time in milliseconds.
     */
    fun insertMarker(label: String, currentTimeMs: Long) {
        lock.withLock {
            if (!active) return

            val escaped = label.replace(",", ";").replace("\n", " ").replace("\r", "")
            try {
                fileWriter.writeLine("$currentTimeMs,,,,$escaped")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to write marker", e)
                return
            }
            markerCount++
        }
    }

    /**
     * Stop capturing and close the file.
     *
     * @param currentTimeMs Current epoch time in milliseconds.
     * @return [BleCaptureMetadata] for the completed capture, or null if not capturing.
     */
    fun stop(currentTimeMs: Long): BleCaptureMetadata? = lock.withLock {
        if (!active) return@withLock null

        fileWriter.close()
        active = false

        val durationSec = (currentTimeMs - startTimeMs) / 1000L

        BleCaptureMetadata(
            fileName = fileName,
            startTimeMillis = startTimeMs,
            endTimeMillis = currentTimeMs,
            durationSeconds = durationSec,
            packetCount = packetCount,
            rxPacketCount = rxCount,
            txPacketCount = txCount,
            markerCount = markerCount,
            wheelTypeName = wheelTypeName,
            wheelName = wheelName
        )
    }

    companion object {
        private const val TAG = "BleCaptureLogger"
        private const val CSV_HEADER = "timestamp_ms,direction,length,hex_data,marker"
    }
}

/**
 * Format epoch millis as "yyyy_MM_dd_HH_mm_ss" for the capture header.
 * Reuses PlatformDateFormatter.formatRideFilename() which uses the same format.
 */
private fun formatCaptureTimestamp(epochMs: Long): String {
    return org.freewheel.core.utils.PlatformDateFormatter.formatRideFilename(epochMs)
}
