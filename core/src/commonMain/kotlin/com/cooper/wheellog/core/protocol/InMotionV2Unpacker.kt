package com.cooper.wheellog.core.protocol

/**
 * Frame unpacker for InMotion V2 protocol wheels (V11, V12, V13, V14, etc.).
 *
 * InMotion V2 uses a CAN-over-BLE protocol with the following frame format:
 * - Bytes 0-1: Header (AA AA)
 * - Byte 2: Flags (0x11 = Initial, 0x14 = Default)
 * - Byte 3: Length (length of data + 1 for command byte)
 * - Byte 4: Command
 * - Bytes 5-(len+3): Data payload
 * - Byte (len+4): Checksum (XOR of bytes 2 to len+3)
 *
 * Escape sequence: 0xA5 is used to escape special bytes (AA, 55, A5)
 * When 0xA5 is encountered, the next byte is taken as literal data.
 */
internal class InMotionV2Unpacker : Unpacker {

    private enum class State {
        UNKNOWN,
        FLAG_SEARCH,
        LEN_SEARCH,
        COLLECTING,
        DONE
    }

    private var buffer = ByteArrayBuilder()
    private var state = State.UNKNOWN
    private var oldC = 0
    private var len = 0
    private var flags = 0

    /**
     * Reset the unpacker state.
     */
    override fun reset() {
        buffer = ByteArrayBuilder()
        state = State.UNKNOWN
        oldC = 0
        len = 0
        flags = 0
    }

    /**
     * Get the complete frame buffer.
     */
    override fun getBuffer(): ByteArray = buffer.toByteArray()

    /**
     * Add a byte to the unpacker.
     * @return true if a complete valid frame is ready
     */
    override fun addChar(c: Int): Boolean {
        val byte = c and 0xFF

        // Handle escape byte 0xA5
        // When we see 0xA5, we skip it and use the next byte as literal data
        if (byte != 0xA5 || oldC == 0xA5) {
            when (state) {
                State.COLLECTING -> {
                    buffer.write(byte)
                    // Frame is complete when buffer size = len + 5
                    // (header(2) + flags(1) + len(1) + command(1) + data(len-1) + checksum(1))
                    // = 2 + 1 + 1 + len + 1 = len + 5
                    if (buffer.size() == len + 5) {
                        state = State.DONE
                        oldC = 0
                        return true
                    }
                }

                State.LEN_SEARCH -> {
                    buffer.write(byte)
                    len = byte
                    state = State.COLLECTING
                    oldC = byte
                }

                State.FLAG_SEARCH -> {
                    buffer.write(byte)
                    flags = byte
                    state = State.LEN_SEARCH
                    oldC = byte
                }

                else -> {
                    // Looking for frame header (AA AA)
                    if (byte == 0xAA && oldC == 0xAA) {
                        buffer = ByteArrayBuilder()
                        buffer.write(0xAA)
                        buffer.write(0xAA)
                        state = State.FLAG_SEARCH
                    }
                    oldC = byte
                }
            }
        } else {
            // This is the escape byte 0xA5, just record it and wait for next byte
            oldC = byte
        }

        return false
    }
}
