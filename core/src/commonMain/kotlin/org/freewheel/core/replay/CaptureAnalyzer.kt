package org.freewheel.core.replay

import org.freewheel.core.domain.WheelState
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
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
 * A field that changed between two [WheelState] instances.
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
    val unhandledReasons: Map<String, Int>
)

/**
 * Complete analysis of a BLE capture file.
 */
data class CaptureAnalysis(
    val header: CaptureHeader,
    val packets: List<AnnotatedPacket>,
    val summary: AnalysisSummary,
    val finalState: WheelState
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
        sb.appendLine("=== Capture Analysis: ${header.wheelTypeName} (${header.wheelName}) ===")
        sb.appendLine("Firmware: ${header.firmware}  Duration: ${summary.durationMs}ms")
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
        var state = WheelState()
        var successCount = 0
        var bufferingCount = 0
        var unhandledCount = 0
        var errorCount = 0
        var rxCount = 0
        var txCount = 0
        val unhandledReasons = mutableMapOf<String, Int>()

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
                        when (val decodeResult = decoder.decode(packet.data, state, config)) {
                            is DecodeResult.Success -> {
                                val newState = decodeResult.data.newState
                                stateChanges = diffStates(state, newState)
                                commandNames = decodeResult.data.commands.map { it::class.simpleName ?: "?" }
                                state = newState
                                successCount++
                                packetResult = PacketResult.Success
                            }
                            is DecodeResult.Buffering -> {
                                bufferingCount++
                                packetResult = PacketResult.Buffering
                            }
                            is DecodeResult.Unhandled -> {
                                unhandledCount++
                                unhandledReasons[decodeResult.reason] =
                                    (unhandledReasons[decodeResult.reason] ?: 0) + 1
                                packetResult = PacketResult.Unhandled(decodeResult.reason)
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

        val summary = AnalysisSummary(
            totalPackets = rxCount + txCount,
            rxPackets = rxCount,
            txPackets = txCount,
            successCount = successCount,
            bufferingCount = bufferingCount,
            unhandledCount = unhandledCount,
            errorCount = errorCount,
            durationMs = capture.durationMs,
            unhandledReasons = unhandledReasons
        )

        return CaptureAnalysis(
            header = capture.header,
            packets = annotatedPackets,
            summary = summary,
            finalState = state
        )
    }
}

/**
 * Compare two [WheelState] instances and return a list of fields that changed.
 * Covers core telemetry, identity, settings, and status fields.
 */
fun diffStates(old: WheelState, new: WheelState): List<StateChange> {
    val changes = mutableListOf<StateChange>()

    fun <T> check(name: String, extract: (WheelState) -> T) {
        val oldVal = extract(old)
        val newVal = extract(new)
        if (oldVal != newVal) {
            changes.add(StateChange(name, oldVal.toString(), newVal.toString()))
        }
    }

    // Core telemetry
    check("speed") { it.speed }
    check("voltage") { it.voltage }
    check("current") { it.current }
    check("phaseCurrent") { it.phaseCurrent }
    check("power") { it.power }
    check("temperature") { it.temperature }
    check("temperature2") { it.temperature2 }
    check("batteryLevel") { it.batteryLevel }
    check("bmsSoc") { it.bmsSoc }

    // Distance
    check("totalDistance") { it.totalDistance }
    check("totalEnergyWh") { it.totalEnergyWh }
    check("wheelDistance") { it.wheelDistance }
    check("topSpeed") { it.topSpeed }
    check("rideTime") { it.rideTime }
    check("totalOnTime") { it.totalOnTime }

    // PWM and output
    check("output") { it.output }
    check("calculatedPwm") { it.calculatedPwm }

    // Orientation
    check("angle") { it.angle }
    check("roll") { it.roll }

    // Motor (IM2)
    check("torque") { it.torque }
    check("motorPower") { it.motorPower }
    check("cpuTemp") { it.cpuTemp }
    check("imuTemp") { it.imuTemp }
    check("cpuLoad") { it.cpuLoad }
    check("hwFaults") { it.hwFaults }
    check("speedLimit") { it.speedLimit }
    check("currentLimit") { it.currentLimit }

    // Status flags
    check("fanStatus") { it.fanStatus }
    check("chargingStatus") { it.chargingStatus }
    check("wheelAlarm") { it.wheelAlarm }

    // Identity
    check("wheelType") { it.wheelType }
    check("name") { it.name }
    check("model") { it.model }
    check("modeStr") { it.modeStr }
    check("version") { it.version }
    check("serialNumber") { it.serialNumber }
    check("btName") { it.btName }

    // BMS (track presence changes)
    check("bms1") { it.bms1 }
    check("bms2") { it.bms2 }

    // Settings
    check("inMiles") { it.inMiles }
    check("pedalsMode") { it.pedalsMode }
    check("speedAlarms") { it.speedAlarms }
    check("rollAngle") { it.rollAngle }
    check("tiltBackSpeed") { it.tiltBackSpeed }
    check("lightMode") { it.lightMode }
    check("ledMode") { it.ledMode }
    check("cutoutAngle") { it.cutoutAngle }
    check("beeperVolume") { it.beeperVolume }
    check("ksAlarm1Speed") { it.ksAlarm1Speed }
    check("ksAlarm2Speed") { it.ksAlarm2Speed }
    check("ksAlarm3Speed") { it.ksAlarm3Speed }
    check("ksTiltbackSpeed") { it.ksTiltbackSpeed }
    check("weakMagnetism") { it.weakMagnetism }
    check("extendedRollAngle") { it.extendedRollAngle }
    check("powerAlarm") { it.powerAlarm }
    check("plateProtection") { it.plateProtection }
    check("maxSpeed") { it.maxSpeed }
    check("pedalTilt") { it.pedalTilt }
    check("pedalSensitivity") { it.pedalSensitivity }
    check("rideMode") { it.rideMode }
    check("fancierMode") { it.fancierMode }
    check("speakerVolume") { it.speakerVolume }
    check("mute") { it.mute }
    check("handleButton") { it.handleButton }
    check("drl") { it.drl }
    check("lightBrightness") { it.lightBrightness }
    check("transportMode") { it.transportMode }
    check("goHomeMode") { it.goHomeMode }
    check("fanQuiet") { it.fanQuiet }

    // Error tracking
    check("error") { it.error }
    check("faultCode") { it.faultCode }
    check("alert") { it.alert }

    return changes
}
