package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType

/**
 * Auto-detect decoder that examines packet headers to determine
 * the actual wheel type and delegates to the appropriate decoder.
 *
 * This handles the case where the wheel type is initially unknown
 * (GOTWAY_VIRTUAL) and needs to be auto-detected from the BLE data.
 *
 * Supported detection:
 * - AA AA header -> Leaperkim CAN wheel
 * - DC 5A 5C header -> Veteran wheel
 * - 55 AA header -> Gotway/Begode wheel
 */
class AutoDetectDecoder(
    private val factory: WheelDecoderFactory = DefaultWheelDecoderFactory()
) : WheelDecoder {

    override val wheelType: WheelType = WheelType.GOTWAY_VIRTUAL

    private var detectedDecoder: WheelDecoder? = null
    private var detectedType: WheelType? = null

    // Lazy initialization of decoders via factory
    private val gotwayDecoder by lazy { factory.createDecoder(WheelType.GOTWAY)!! }
    private val veteranDecoder by lazy { factory.createDecoder(WheelType.VETERAN)!! }
    private val leaperkimDecoder by lazy { factory.createDecoder(WheelType.LEAPERKIM)!! }

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodeResult {
        if (data.isEmpty()) return DecodeResult.Buffering

        // If we've already detected the wheel type, delegate to that decoder
        detectedDecoder?.let { decoder ->
            return decoder.decode(data, currentState, config)
        }

        // Try to detect wheel type from header bytes
        if (data.size >= 3) {
            when {
                // Leaperkim CAN header: AA AA
                data[0] == 0xAA.toByte() &&
                data[1] == 0xAA.toByte() -> {
                    detectedDecoder = leaperkimDecoder
                    detectedType = WheelType.LEAPERKIM
                    return leaperkimDecoder.decode(data, currentState, config).withWheelType(WheelType.LEAPERKIM)
                }

                // Veteran header: DC 5A 5C
                data[0] == 0xDC.toByte() &&
                data[1] == 0x5A.toByte() &&
                data[2] == 0x5C.toByte() -> {
                    detectedDecoder = veteranDecoder
                    detectedType = WheelType.VETERAN
                    return veteranDecoder.decode(data, currentState, config).withWheelType(WheelType.VETERAN)
                }

                // Gotway header: 55 AA
                data[0] == 0x55.toByte() &&
                data[1] == 0xAA.toByte() -> {
                    detectedDecoder = gotwayDecoder
                    detectedType = WheelType.GOTWAY
                    return gotwayDecoder.decode(data, currentState, config).withWheelType(WheelType.GOTWAY)
                }
            }
        }

        // Not enough data or unrecognized header
        return DecodeResult.Unhandled(
            reason = UnhandledReason(UnhandledReason.ErrorClass.UNRECOGNIZED_HEADER),
            frameData = data.copyOf()
        )
    }

    /** Stamps wheelType on success results from delegated decoders. */
    private fun DecodeResult.withWheelType(type: WheelType): DecodeResult = when (this) {
        is DecodeResult.Success -> {
            val ws = data.newState
            if (ws != null) {
                DecodeResult.Success(data.copy(newState = ws.copy(wheelType = type)))
            } else {
                // Migrated decoder: stamp identity instead
                val id = data.identity ?: WheelIdentity()
                DecodeResult.Success(data.copy(identity = id.copy(wheelType = type)))
            }
        }
        is DecodeResult.Buffering -> this
        is DecodeResult.Unhandled -> this
    }

    override fun isReady(): Boolean {
        return detectedDecoder?.isReady() ?: false
    }

    override fun reset() {
        detectedDecoder?.reset()
        detectedDecoder = null
        detectedType = null
    }

    override fun getInitCommands(): List<WheelCommand> {
        // Return Gotway init commands plus Leaperkim password command,
        // so CAN wheels in auto-detect mode receive the password immediately
        return gotwayDecoder.getInitCommands() + leaperkimDecoder.getInitCommands()
    }

    /**
     * Get the detected wheel type, or null if not yet detected.
     */
    fun getDetectedType(): WheelType? = detectedType

    /**
     * Get the underlying decoder, or null if not yet detected.
     */
    fun getDetectedDecoder(): WheelDecoder? = detectedDecoder
}
