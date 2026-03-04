package org.freewheel.core.protocol

/**
 * Frame unpacker for InMotion wheels (V1 protocol).
 *
 * InMotion uses a CAN-over-BLE protocol with the following frame format:
 * - Bytes 0-1: Header (AA AA)
 * - Bytes 2-N: Data payload (with 0xA5 escape bytes)
 * - Byte N+1: Checksum
 * - Bytes N+2,N+3: Footer (55 55)
 *
 * Escape sequence: 0xA5 followed by the escaped byte (AA, 55, or A5)
 * The escape byte 0xA5 is used to escape special bytes in the data.
 */
internal class InMotionUnpacker : Unpacker {

    private enum class State {
        UNKNOWN,
        COLLECTING,
        DONE
    }

    private var buffer = ByteArrayBuilder()
    private var state = State.UNKNOWN
    private var oldC = 0

    // Packet length tracking
    // There are two types of packets: basic and extended.
    // For extended packets, len field is 0xFE, and the extended data length
    // is stored in the first data bytes of the packet.
    private var lenBasic = 0    // Basic packet length field
    private var lenExtended = 0 // Extended packet data length

    /**
     * Reset the unpacker state.
     */
    override fun reset() {
        buffer = ByteArrayBuilder()
        state = State.UNKNOWN
        oldC = 0
        lenBasic = 0
        lenExtended = 0
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

        // Skip escaped bytes (0xA5 is the escape prefix)
        // When we see 0xA5 followed by another byte, we skip 0xA5 and use the next byte as data
        if (byte != 0xA5 || oldC == 0xA5) {
            when (state) {
                State.COLLECTING -> {
                    buffer.write(byte)
                    val size = buffer.size()

                    // At byte 7 (0-indexed: position 6), we read the extended length from first data byte
                    if (size == 7) {
                        lenExtended = byte
                    }
                    // At byte 15 (0-indexed: position 14), we read the basic packet length
                    else if (size == 15) {
                        lenBasic = byte
                    }

                    // Check if packet is longer than expected (extended packet)
                    // Header(2) + ID(4) + data(8) + len(1) + ch(1) + format(1) + type(1) + extended_data + checksum(1) + footer(2)
                    // = 18 + extended_data + 3 = 21 + extended_data
                    if (size > lenExtended + 21 && lenBasic == 0xFE) {
                        // Packet is longer than expected, reset
                        reset()
                        return false
                    }

                    // Check for footer (55 55)
                    // Complete frame when we have footer and either:
                    // - Extended packet: size matches expected length (lenExtended + 21)
                    // - Basic packet: lenBasic != 0xFE
                    if (byte == 0x55 && oldC == 0x55) {
                        val isExtendedPacket = lenBasic == 0xFE
                        val expectedExtendedSize = lenExtended + 21

                        if ((size == expectedExtendedSize) || !isExtendedPacket) {
                            state = State.DONE
                            oldC = 0
                            return true
                        }
                    }
                }

                else -> {
                    // Looking for frame header (AA AA)
                    if (byte == 0xAA && oldC == 0xAA) {
                        buffer = ByteArrayBuilder()
                        buffer.write(0xAA)
                        buffer.write(0xAA)
                        state = State.COLLECTING
                        lenBasic = 0
                        lenExtended = 0
                    }
                }
            }
        }

        oldC = byte
        return false
    }
}
