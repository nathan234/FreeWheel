package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings

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
 * Shared decode loop for decoders that use an [Unpacker] for frame reassembly.
 *
 * Feeds each byte from [data] through the [unpacker], and when a complete frame
 * is assembled, passes it to [processFrame] for protocol-specific processing.
 *
 * The unpacker is automatically reset after each frame extraction, enabling
 * multi-frame BLE notifications for all decoders.
 *
 * Accumulates state as [DecoderState] (domain pieces). The lambda receives the
 * accumulated [DecoderState] and returns a [FrameResult] with domain pieces.
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
 *   returning a [FrameResult] or null if the frame is invalid/unrecognized
 * @return [DecodeResult] indicating success, unhandled, or buffering
 */
internal inline fun decodeFrames(
    data: ByteArray,
    unpacker: Unpacker,
    currentState: DecoderState,
    processFrame: (buffer: ByteArray, state: DecoderState) -> FrameResult?
): DecodeResult {
    var state = currentState
    var hasNewData = false
    var frameProcessed = false
    val commands = mutableListOf<WheelCommand>()
    var news: String? = null
    var hadCompleteFrame = false
    var firstUnhandledBuffer: ByteArray? = null
    val frameTypes = mutableListOf<String>()
    val logEntries = mutableListOf<EventLogEntry>()

    for (byte in data) {
        if (unpacker.addChar(byte.toInt() and 0xFF)) {
            val buffer = unpacker.getBuffer()
            unpacker.reset()
            hadCompleteFrame = true
            val result = processFrame(buffer, state)
            if (result != null) {
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
            } else if (firstUnhandledBuffer == null) {
                firstUnhandledBuffer = buffer.copyOf()
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
            DecodeResult.Unhandled(
                reason = UnhandledReason(UnhandledReason.ErrorClass.UNKNOWN_COMMAND),
                frameData = firstUnhandledBuffer ?: byteArrayOf()
            )
        }
        else -> DecodeResult.Buffering
    }
}
