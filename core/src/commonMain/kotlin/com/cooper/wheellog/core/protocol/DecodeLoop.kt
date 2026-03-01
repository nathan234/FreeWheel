package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.WheelState

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
 * Returns null only when no frame was processed and no state change occurred —
 * this eliminates the null-return-on-valid-frame bug that previously existed
 * when each decoder copy-pasted this loop.
 *
 * @param data Raw bytes from a BLE notification
 * @param unpacker The protocol-specific frame unpacker
 * @param currentState Current wheel state to build upon
 * @param processFrame Lambda that processes a single complete frame buffer,
 *   returning a [FrameResult] or null if the frame is invalid/unrecognized
 * @return [DecodedData] if any frame was processed or state changed, null otherwise
 */
internal inline fun decodeFrames(
    data: ByteArray,
    unpacker: Unpacker,
    currentState: WheelState,
    processFrame: (buffer: ByteArray, state: WheelState) -> FrameResult?
): DecodedData? {
    var newState = currentState
    var hasNewData = false
    var frameProcessed = false
    val commands = mutableListOf<WheelCommand>()
    var news: String? = null

    for (byte in data) {
        if (unpacker.addChar(byte.toInt() and 0xFF)) {
            val buffer = unpacker.getBuffer()
            unpacker.reset()
            val result = processFrame(buffer, newState)
            if (result != null) {
                frameProcessed = true
                newState = result.state
                hasNewData = hasNewData || result.hasNewData
                commands.addAll(result.commands)
                result.news?.let { news = it }
            }
        }
    }

    return if (frameProcessed || hasNewData || newState != currentState) {
        DecodedData(newState, commands, hasNewData, news)
    } else null
}
