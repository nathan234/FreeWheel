package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelState

/**
 * Result from processing a single unpacked frame within a decoder.
 *
 * Supports two paths:
 * - **Legacy**: set [state] to a full WheelState. Domain pieces are extracted automatically.
 * - **Migrated**: set domain piece fields ([telemetry], [identity], [bms], [settings]) directly;
 *   leave [state] null. Only non-null pieces are merged into accumulated state.
 */
data class FrameResult(
    /** Legacy: full updated WheelState. Set by unmigrated processFrame methods. */
    val state: WheelState? = null,
    // Domain pieces (set by migrated processFrame methods; non-null = changed)
    val telemetry: TelemetryState? = null,
    val identity: WheelIdentity? = null,
    val bms: BmsState? = null,
    val settings: WheelSettings? = null,
    val hasNewData: Boolean = false,
    val commands: List<WheelCommand> = emptyList(),
    val news: String? = null,
    val frameType: String? = null
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
 * accumulated [DecoderState] and returns a [FrameResult] with either a legacy
 * [WheelState] or domain pieces directly.
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

    for (byte in data) {
        if (unpacker.addChar(byte.toInt() and 0xFF)) {
            val buffer = unpacker.getBuffer()
            unpacker.reset()
            hadCompleteFrame = true
            val result = processFrame(buffer, state)
            if (result != null) {
                frameProcessed = true
                state = if (result.state != null) {
                    // Legacy path: extract domain pieces from WheelState
                    DecoderState(
                        telemetry = result.state.toTelemetryState(),
                        identity = result.state.toIdentity(),
                        bms = result.state.toBmsState(),
                        settings = result.state.toWheelSettings()
                    )
                } else {
                    // Migrated path: merge domain pieces directly
                    DecoderState(
                        telemetry = result.telemetry ?: state.telemetry,
                        identity = result.identity ?: state.identity,
                        bms = result.bms ?: state.bms,
                        settings = result.settings ?: state.settings
                    )
                }
                hasNewData = hasNewData || result.hasNewData
                commands.addAll(result.commands)
                result.news?.let { news = it }
                result.frameType?.let { frameTypes.add(it) }
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
                frameTypes = frameTypes
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
