package org.freewheel.core.protocol

/**
 * Frame unpacker for Ninebot wheels.
 *
 * Frame format:
 * - Bytes 0-1: Header (55 AA)
 * - Byte 2: Length (of data portion)
 * - Bytes 3+: CAN message data (source, destination, parameter, data)
 * - Last 2 bytes: CRC16 checksum
 *
 * Total frame size = length + 6 (header + length byte + data + CRC)
 */
internal class NinebotUnpacker : Unpacker {

    private enum class State {
        UNKNOWN,
        STARTED,
        COLLECTING,
        DONE
    }

    private var buffer = ByteArrayBuilder()
    private var oldC = 0
    private var len = 0
    private var state = State.UNKNOWN

    /**
     * Reset the unpacker state.
     */
    override fun reset() {
        buffer = ByteArrayBuilder()
        oldC = 0
        len = 0
        state = State.UNKNOWN
    }

    /**
     * Get the complete frame buffer.
     */
    override fun getBuffer(): ByteArray = buffer.toByteArray()

    /**
     * Add a byte to the unpacker.
     * @param c The byte value (0-255)
     * @return true if a complete valid frame is ready
     */
    override fun addChar(c: Int): Boolean {
        val byte = c and 0xFF

        when (state) {
            State.COLLECTING -> {
                buffer.write(byte)
                // Frame complete when we have: header(2) + length(1) + data(len) + CRC(2) = len + 5
                // But buffer already includes header, so check for len + 6 total
                if (buffer.size() == len + 6) {
                    state = State.DONE
                    return true
                }
            }

            State.STARTED -> {
                buffer.write(byte)
                len = byte
                state = State.COLLECTING
            }

            else -> {
                // Looking for frame header (55 AA)
                if (byte == 0xAA && oldC == 0x55) {
                    buffer = ByteArrayBuilder()
                    buffer.write(0x55)
                    buffer.write(0xAA)
                    state = State.STARTED
                }
                oldC = byte
            }
        }

        return false
    }
}
