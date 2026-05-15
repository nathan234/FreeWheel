package org.freewheel.core.replay

import org.freewheel.core.domain.telemetry.BmsState
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.settings.WheelSettings
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.UnpackerStats
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.utils.ByteUtils

/**
 * Result of decoding a single packet during analysis.
 */
sealed class PacketResult {
    /** Decoder produced a new state. */
    data object Success : PacketResult()

    /** Unpacker is assembling a multi-packet frame. */
    data object Buffering : PacketResult()

    /** Decoder received a complete frame but doesn't recognize it. */
    data class Unhandled(val reason: String) : PacketResult()

    /** Decoder threw an exception. */
    data class Error(val message: String) : PacketResult()

    /** TX packet — not fed to decoder. */
    data object Skipped : PacketResult()

    val label: String get() = when (this) {
        is Success -> "success"
        is Buffering -> "buffering"
        is Unhandled -> "unhandled:$reason"
        is Error -> "error:$message"
        is Skipped -> "skipped"
    }
}

/**
 * A field that changed between two [DecoderState] instances.
 */
data class StateChange(
    val field: String,
    val oldValue: String,
    val newValue: String
)

/**
 * A single packet with its decode result and state changes annotated.
 */
data class AnnotatedPacket(
    val index: Int,
    val timestampMs: Long,
    val direction: BlePacketDirection,
    val hexData: String,
    val result: PacketResult,
    val stateChanges: List<StateChange>,
    val commands: List<String>
)

/**
 * Frame type distribution entry for a single frame type.
 */
data class FrameTypeEntry(
    val frameType: String,
    val count: Int,
    val isUnhandled: Boolean
)

/**
 * Summary statistics for a capture analysis run.
 */
data class AnalysisSummary(
    val totalPackets: Int,
    val rxPackets: Int,
    val txPackets: Int,
    val successCount: Int,
    val bufferingCount: Int,
    val unhandledCount: Int,
    val errorCount: Int,
    val durationMs: Long,
    val unhandledReasons: Map<String, Int>,
    val frameTypeDistribution: List<FrameTypeEntry> = emptyList(),
    val unpackerStats: UnpackerStats = UnpackerStats()
)

/**
 * Complete analysis of a BLE capture file.
 */
data class CaptureAnalysis(
    val header: CaptureHeader,
    val packets: List<AnnotatedPacket>,
    val summary: AnalysisSummary,
    val finalState: DecoderState
) {
    /**
     * Format analysis as a human-readable report.
     *
     * Each RX packet is shown as:
     * ```
     * timestamp | direction | hex | decode_result | state_delta
     * ```
     */
    fun formatReport(includeBuffering: Boolean = false, maxHexLength: Int = 40): String {
        val sb = StringBuilder()

        // Header
        val durationStr = formatDuration(summary.durationMs)
        sb.appendLine("=== Capture Analysis: ${header.wheelTypeName} (${header.wheelName}) ===")
        sb.appendLine("Firmware: ${header.firmware}  Duration: $durationStr")
        sb.appendLine()

        // Packet table
        for (packet in packets) {
            if (packet.result is PacketResult.Skipped) continue
            if (!includeBuffering && packet.result is PacketResult.Buffering) continue

            val hex = if (packet.hexData.length > maxHexLength) {
                packet.hexData.take(maxHexLength) + "..."
            } else {
                packet.hexData
            }

            val delta = if (packet.stateChanges.isEmpty()) {
                ""
            } else {
                packet.stateChanges.joinToString(", ") { "${it.field}:${it.oldValue}->${it.newValue}" }
            }

            sb.appendLine("${packet.timestampMs} | ${packet.direction} | $hex | ${packet.result.label} | $delta")
        }

        sb.appendLine()

        // Summary
        sb.appendLine("--- Summary ---")
        sb.appendLine("Total: ${summary.totalPackets} (RX: ${summary.rxPackets}, TX: ${summary.txPackets})")
        sb.appendLine("Success: ${summary.successCount}  Buffering: ${summary.bufferingCount}  Unhandled: ${summary.unhandledCount}  Error: ${summary.errorCount}")

        // Frame type distribution histogram
        if (summary.frameTypeDistribution.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- Frame Type Distribution ---")
            val maxNameLen = summary.frameTypeDistribution.maxOf { it.frameType.length }
            for (entry in summary.frameTypeDistribution) {
                val name = entry.frameType.padEnd(maxNameLen)
                val status = if (entry.isUnhandled) "<- unhandled" else "ok"
                sb.appendLine("  $name: ${entry.count.toString().padStart(6)} frames  $status")
            }
        }

        // Unpacker stats
        if (summary.unpackerStats.errorResets > 0 || summary.unpackerStats.bytesDiscarded > 0) {
            sb.appendLine("  Unpacker resets: ${summary.unpackerStats.errorResets} (${summary.unpackerStats.bytesDiscarded} bytes discarded)")
        }

        if (summary.unhandledReasons.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Unhandled reasons:")
            for ((reason, count) in summary.unhandledReasons.entries.sortedByDescending { it.value }) {
                sb.appendLine("  $reason: $count")
            }
        }

        return sb.toString()
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}m ${sec}s" else "${sec}s"
}

/**
 * Synchronous analysis engine for BLE capture files.
 *
 * Unlike [ReplayEngine] (which plays back in real-time with delays for UI),
 * this engine runs through all packets immediately and produces per-packet
 * annotated results with state deltas and summary statistics.
 *
 * Primary use cases:
 * - Debugging user captures without their wheel
 * - Golden capture integration tests (assert on summary/final state)
 * - Foundation for frame distribution analysis and differential testing
 */
class CaptureAnalyzer(
    private val decoderFactory: WheelDecoderFactory = DefaultWheelDecoderFactory()
) {

    /**
     * Analyze a capture file by feeding all RX packets through the appropriate decoder.
     *
     * @param capture Parsed capture file from [BleCaptureReader].
     * @param config Decoder configuration (unit preferences, wheel-specific settings).
     * @return Analysis results, or null if the wheel type is unsupported.
     */
    fun analyze(capture: CaptureFile, config: DecoderConfig = DecoderConfig()): CaptureAnalysis? {
        val decoder = decoderFactory.createDecoder(capture.header.wheelType) ?: return null

        val annotatedPackets = mutableListOf<AnnotatedPacket>()
        var ds = DecoderState()
        var successCount = 0
        var bufferingCount = 0
        var unhandledCount = 0
        var errorCount = 0
        var rxCount = 0
        var txCount = 0
        val unhandledReasons = mutableMapOf<String, Int>()
        val successFrameTypes = mutableMapOf<String, Int>()
        val unhandledFrameTypes = mutableMapOf<String, Int>()

        for ((index, entry) in capture.entries.withIndex()) {
            when (entry) {
                is CaptureEntry.Marker -> continue
                is CaptureEntry.Packet -> {
                    val packet = entry.packet
                    val hex = ByteUtils.bytesToHex(packet.data)

                    if (packet.direction == BlePacketDirection.TX) {
                        txCount++
                        annotatedPackets.add(AnnotatedPacket(
                            index = index,
                            timestampMs = packet.timestampMs,
                            direction = packet.direction,
                            hexData = hex,
                            result = PacketResult.Skipped,
                            stateChanges = emptyList(),
                            commands = emptyList()
                        ))
                        continue
                    }

                    rxCount++

                    var packetResult: PacketResult
                    var stateChanges: List<StateChange> = emptyList()
                    var commandNames: List<String> = emptyList()

                    try {
                        when (val decodeResult = decoder.decode(packet.data, ds, config)) {
                            is DecodeResult.Success -> {
                                val d = decodeResult.data
                                val oldDs = ds
                                d.telemetry?.let { ds = ds.copy(telemetry = it) }
                                d.identity?.let { ds = ds.copy(identity = it) }
                                d.bms?.let { ds = ds.copy(bms = it) }
                                d.settings?.let { ds = ds.copy(settings = it) }
                                if (ds != oldDs) {
                                    stateChanges = diffStates(oldDs, ds)
                                }
                                commandNames = decodeResult.data.commands.map { it::class.simpleName ?: "?" }
                                successCount++
                                packetResult = PacketResult.Success
                                for (ft in decodeResult.data.frameTypes) {
                                    successFrameTypes[ft] = (successFrameTypes[ft] ?: 0) + 1
                                }
                            }
                            is DecodeResult.Buffering -> {
                                bufferingCount++
                                packetResult = PacketResult.Buffering
                            }
                            is DecodeResult.Unhandled -> {
                                unhandledCount++
                                val reasonStr = decodeResult.reason.toString()
                                unhandledReasons[reasonStr] =
                                    (unhandledReasons[reasonStr] ?: 0) + 1
                                packetResult = PacketResult.Unhandled(reasonStr)
                                val unhandledType = decodeResult.reason.detail.ifEmpty { reasonStr }
                                unhandledFrameTypes[unhandledType] = (unhandledFrameTypes[unhandledType] ?: 0) + 1
                            }
                        }
                    } catch (e: Exception) {
                        errorCount++
                        packetResult = PacketResult.Error(e.message ?: "unknown")
                    }

                    annotatedPackets.add(AnnotatedPacket(
                        index = index,
                        timestampMs = packet.timestampMs,
                        direction = packet.direction,
                        hexData = hex,
                        result = packetResult,
                        stateChanges = stateChanges,
                        commands = commandNames
                    ))
                }
            }
        }

        // Build frame type distribution: successful types first (sorted by count desc),
        // then unhandled types (sorted by count desc)
        val distribution = mutableListOf<FrameTypeEntry>()
        for ((ft, count) in successFrameTypes.entries.sortedByDescending { it.value }) {
            distribution.add(FrameTypeEntry(ft, count, isUnhandled = false))
        }
        for ((ft, count) in unhandledFrameTypes.entries.sortedByDescending { it.value }) {
            distribution.add(FrameTypeEntry(ft, count, isUnhandled = true))
        }

        val summary = AnalysisSummary(
            totalPackets = rxCount + txCount,
            rxPackets = rxCount,
            txPackets = txCount,
            successCount = successCount,
            bufferingCount = bufferingCount,
            unhandledCount = unhandledCount,
            errorCount = errorCount,
            durationMs = capture.durationMs,
            unhandledReasons = unhandledReasons,
            frameTypeDistribution = distribution,
            unpackerStats = decoder.getUnpackerStats() ?: UnpackerStats()
        )

        return CaptureAnalysis(
            header = capture.header,
            packets = annotatedPackets,
            summary = summary,
            finalState = ds
        )
    }
}

/**
 * Compare two [DecoderState] instances and return a list of fields that changed.
 * Checks each sub-type's fields (TelemetryState, WheelIdentity, BmsState, WheelSettings).
 */
fun diffStates(old: DecoderState, new: DecoderState): List<StateChange> {
    val changes = mutableListOf<StateChange>()

    // Telemetry
    if (old.telemetry != new.telemetry) {
        fun <T> checkTel(name: String, extract: (TelemetryState) -> T) {
            val oldVal = extract(old.telemetry)
            val newVal = extract(new.telemetry)
            if (oldVal != newVal) changes.add(StateChange(name, oldVal.toString(), newVal.toString()))
        }
        checkTel("speed") { it.speed }
        checkTel("voltage") { it.voltage }
        checkTel("current") { it.current }
        checkTel("phaseCurrent") { it.phaseCurrent }
        checkTel("power") { it.power }
        checkTel("temperature") { it.temperature }
        checkTel("temperature2") { it.temperature2 }
        checkTel("batteryLevel") { it.batteryLevel }
        checkTel("bmsSoc") { it.bmsSoc }
        checkTel("totalDistance") { it.totalDistance }
        checkTel("totalEnergyWh") { it.totalEnergyWh }
        checkTel("wheelDistance") { it.wheelDistance }
        checkTel("topSpeed") { it.topSpeed }
        checkTel("rideTime") { it.rideTime }
        checkTel("totalOnTime") { it.totalOnTime }
        checkTel("output") { it.output }
        checkTel("calculatedPwm") { it.calculatedPwm }
        checkTel("angle") { it.angle }
        checkTel("roll") { it.roll }
        checkTel("torque") { it.torque }
        checkTel("motorPower") { it.motorPower }
        checkTel("cpuTemp") { it.cpuTemp }
        checkTel("imuTemp") { it.imuTemp }
        checkTel("cpuLoad") { it.cpuLoad }
        checkTel("hwFaults") { it.hwFaults }
        checkTel("speedLimit") { it.speedLimit }
        checkTel("currentLimit") { it.currentLimit }
        checkTel("fanStatus") { it.fanStatus }
        checkTel("chargingStatus") { it.chargingStatus }
        checkTel("wheelAlarm") { it.wheelAlarm }
        checkTel("error") { it.error }
        checkTel("faultCode") { it.faultCode }
        checkTel("alert") { it.alert }
    }

    // Identity
    if (old.identity != new.identity) {
        fun <T> checkId(name: String, extract: (WheelIdentity) -> T) {
            val oldVal = extract(old.identity)
            val newVal = extract(new.identity)
            if (oldVal != newVal) changes.add(StateChange(name, oldVal.toString(), newVal.toString()))
        }
        checkId("wheelType") { it.wheelType }
        checkId("name") { it.name }
        checkId("model") { it.model }
        checkId("modeStr") { it.modeStr }
        checkId("version") { it.version }
        checkId("serialNumber") { it.serialNumber }
        checkId("btName") { it.btName }
    }

    // BMS
    if (old.bms != new.bms) {
        fun <T> checkBms(name: String, extract: (BmsState) -> T) {
            val oldVal = extract(old.bms)
            val newVal = extract(new.bms)
            if (oldVal != newVal) changes.add(StateChange(name, oldVal.toString(), newVal.toString()))
        }
        checkBms("bms1") { it.bms1 }
        checkBms("bms2") { it.bms2 }
    }

    // Settings — compare as opaque sealed type
    if (old.settings != new.settings) {
        changes.add(StateChange("settings", old.settings.toString(), new.settings.toString()))
    }

    return changes
}
