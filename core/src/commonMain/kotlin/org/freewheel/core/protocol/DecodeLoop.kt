package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.utils.ByteUtils

/**
 * Result from processing a single unpacked frame within a decoder.
 *
 * Set domain piece fields ([telemetry], [identity], [bms], [settings]) directly;
 * only non-null pieces are merged into accumulated state.
 */
data class FrameResult(
    val telemetry: TelemetryState? = null,
    val identity: WheelIdentity? = null,
    val bms: BmsState? = null,
    val settings: WheelSettings? = null,
    val hasNewData: Boolean = false,
    val commands: List<WheelCommand> = emptyList(),
    val news: String? = null,
    val frameType: String? = null,
    val logEntries: List<EventLogEntry> = emptyList()
)

/**
 * Outcome of a [processFrame] call within [decodeFrames].
 *
 * Forces the caller to explicitly distinguish between "I recognized this frame"
 * and "I don't know what this frame is." Prevents the bug where a recognized
 * frame processor returns null (meaning "no new data") and [decodeFrames]
 * misinterprets it as an unrecognized frame type.
 */
sealed class FrameOutcome {
    /** Frame was recognized and processed (may or may not have new data). */
    data class Processed(val result: FrameResult) : FrameOutcome()

    /** Frame was not recognized by this decoder — unknown type, failed verification, etc. */
    data class Unrecognized(val hint: String) : FrameOutcome()
}

/**
 * Shared decode loop for decoders that use an [Unpacker] for frame reassembly.
 *
 * Feeds each byte from [data] through the [unpacker], and when a complete frame
 * is assembled, passes it to [processFrame] for protocol-specific processing.
 *
 * The unpacker is automatically reset after each frame extraction, enabling
 * multi-frame BLE notifications for all decoders.
 *
 * Accumulates state as [DecoderState] (domain pieces). The lambda receives the
 * accumulated [DecoderState] and returns a [FrameOutcome] indicating whether the
 * frame was recognized.
 *
 * Returns a [DecodeResult] that distinguishes between:
 * - [DecodeResult.Success] — at least one frame was processed successfully
 * - [DecodeResult.Unhandled] — unpacker yielded frame(s) but none were recognized
 * - [DecodeResult.Buffering] — no complete frames extracted yet
 *
 * @param data Raw bytes from a BLE notification
 * @param unpacker The protocol-specific frame unpacker
 * @param currentState Current decoder state (domain sub-states) to build upon
 * @param processFrame Lambda that processes a single complete frame buffer,
 *   returning [FrameOutcome.Processed] if recognized or [FrameOutcome.Unrecognized] if not
 * @return [DecodeResult] indicating success, unhandled, or buffering
 */
internal inline fun decodeFrames(
    data: ByteArray,
    unpacker: Unpacker,
    currentState: DecoderState,
    processFrame: (buffer: ByteArray, state: DecoderState) -> FrameOutcome
): DecodeResult {
    var state = currentState
    var hasNewData = false
    var frameProcessed = false
    val commands = mutableListOf<WheelCommand>()
    var news: String? = null
    var hadCompleteFrame = false
    var firstUnhandledBuffer: ByteArray? = null
    var firstUnhandledHint: String? = null
    val frameTypes = mutableListOf<String>()
    val logEntries = mutableListOf<EventLogEntry>()

    for (byte in data) {
        if (unpacker.addChar(byte.toInt() and 0xFF)) {
            val buffer = unpacker.getBuffer()
            unpacker.reset()
            hadCompleteFrame = true
            when (val outcome = processFrame(buffer, state)) {
                is FrameOutcome.Processed -> {
                    val result = outcome.result
                    frameProcessed = true
                    state = DecoderState(
                        telemetry = result.telemetry ?: state.telemetry,
                        identity = result.identity ?: state.identity,
                        bms = result.bms ?: state.bms,
                        settings = result.settings ?: state.settings
                    )
                    hasNewData = hasNewData || result.hasNewData
                    commands.addAll(result.commands)
                    result.news?.let { news = it }
                    result.frameType?.let { frameTypes.add(it) }
                    logEntries.addAll(result.logEntries)
                }
                is FrameOutcome.Unrecognized -> {
                    if (firstUnhandledBuffer == null) {
                        firstUnhandledBuffer = buffer.copyOf()
                        firstUnhandledHint = outcome.hint
                    }
                }
            }
        }
    }

    return when {
        frameProcessed || hasNewData || state != currentState -> {
            DecodeResult.Success(DecodedData(
                telemetry = state.telemetry.takeIf { it != currentState.telemetry },
                identity = state.identity.takeIf { it != currentState.identity },
                bms = state.bms.takeIf { it != currentState.bms },
                settings = state.settings.takeIf { it != currentState.settings },
                commands = commands,
                hasNewData = hasNewData,
                news = news,
                frameTypes = frameTypes,
                logEntries = logEntries
            ))
        }
        hadCompleteFrame -> {
            val buf = firstUnhandledBuffer ?: byteArrayOf()
            val hex = ByteUtils.bytesToHex(buf)
            val detail = if (firstUnhandledHint != null) "$firstUnhandledHint $hex" else hex
            DecodeResult.Unhandled(
                reason = UnhandledReason.UnknownCommand(frameHex = detail),
                frameData = buf
            )
        }
        else -> DecodeResult.Buffering
    }
}
