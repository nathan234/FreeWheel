package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState

/**
 * Result from processing a single unpacked frame within a decoder.
 *
 * Replaces the per-decoder private result types (GotwayDecoder.FrameResult,
 * NinebotDecoder.FrameResult, InMotionV2Decoder.ProcessResult, etc.)
 * with a single shared type.
 */
data class FrameResult(
    val state: WheelState,
    val hasNewData: Boolean = false,
    val commands: List<WheelCommand> = emptyList(),
    val news: String? = null
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
 * Returns a [DecodeResult] that distinguishes between:
 * - [DecodeResult.Success] — at least one frame was processed successfully
 * - [DecodeResult.Unhandled] — unpacker yielded frame(s) but none were recognized
 * - [DecodeResult.Buffering] — no complete frames extracted yet
 *
 * @param data Raw bytes from a BLE notification
 * @param unpacker The protocol-specific frame unpacker
 * @param currentState Current wheel state to build upon
 * @param processFrame Lambda that processes a single complete frame buffer,
 *   returning a [FrameResult] or null if the frame is invalid/unrecognized
 * @return [DecodeResult] indicating success, unhandled, or buffering
 */
internal inline fun decodeFrames(
    data: ByteArray,
    unpacker: Unpacker,
    currentState: WheelState,
    processFrame: (buffer: ByteArray, state: WheelState) -> FrameResult?
): DecodeResult {
    var newState = currentState
    var hasNewData = false
    var frameProcessed = false
    val commands = mutableListOf<WheelCommand>()
    var news: String? = null
    var hadCompleteFrame = false
    var firstUnhandledBuffer: ByteArray? = null

    for (byte in data) {
        if (unpacker.addChar(byte.toInt() and 0xFF)) {
            val buffer = unpacker.getBuffer()
            unpacker.reset()
            hadCompleteFrame = true
            val result = processFrame(buffer, newState)
            if (result != null) {
                frameProcessed = true
                newState = result.state
                hasNewData = hasNewData || result.hasNewData
                commands.addAll(result.commands)
                result.news?.let { news = it }
            } else if (firstUnhandledBuffer == null) {
                firstUnhandledBuffer = buffer.copyOf()
            }
        }
    }

    return when {
        frameProcessed || hasNewData || newState != currentState -> {
            DecodeResult.Success(DecodedData(newState, commands, hasNewData, news))
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
