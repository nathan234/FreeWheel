package org.freewheel.core.protocol

import kotlin.math.min

/**
 * Frame unpacker for Gotway/Begode wheels.
 *
 * Gotway uses a serial-over-BLE protocol with 24-byte frames.
 * Frame format:
 * - Bytes 0-1: Header (55 AA)
 * - Bytes 2-17: Data payload
 * - Byte 18: Frame type
 * - Byte 19: Footer byte (typically 18)
 * - Bytes 20-23: Footer (5A 5A 5A 5A)
 */
internal class GotwayUnpacker : Unpacker {

    private enum class State {
        UNKNOWN,
        COLLECTING,
        DONE
    }

    private var buffer = ByteArrayBuilder()
    private var state = State.UNKNOWN
    private var oldC = -1

    /**
     * Reset the unpacker state.
     */
    override fun reset() {
        buffer = ByteArrayBuilder()
        state = State.UNKNOWN
        oldC = -1
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

        when (state) {
            State.COLLECTING -> {
                buffer.write(byte)
                val size = buffer.size()

                // Check footer bytes (should be 5A 5A 5A 5A)
                if (size > 20 && size <= 24 && byte != 0x5A) {
                    // Invalid frame footer
                    state = State.UNKNOWN
                    return false
                }

                if (size == 24) {
                    state = State.DONE
                    return true
                }

                // Handle garbage packet detection (55 AA 5A followed by 55 AA)
                if (size == 5) {
                    val buf = buffer.toByteArray()
                    if (buf[0] == 0x55.toByte() && buf[1] == 0xAA.toByte() &&
                        buf[2] == 0x5A.toByte() && buf[3] == 0x55.toByte() &&
                        buf[4] == 0xAA.toByte()) {
                        // Garbage packet, reassemble
                        buffer = ByteArrayBuilder()
                        buffer.write(0x55)
                        buffer.write(0xAA)
                    }
                }

                // Handle another garbage pattern (55 AA 5A 5A 55 AA)
                if (size == 6) {
                    val buf = buffer.toByteArray()
                    if (buf[0] == 0x55.toByte() && buf[1] == 0xAA.toByte() &&
                        buf[2] == 0x5A.toByte() && buf[3] == 0x5A.toByte() &&
                        buf[4] == 0x55.toByte() && buf[5] == 0xAA.toByte()) {
                        // Garbage packet, reassemble
                        buffer = ByteArrayBuilder()
                        buffer.write(0x55)
                        buffer.write(0xAA)
                    }
                }
            }

            else -> {
                // Looking for frame header (55 AA)
                if (byte == 0xAA && oldC == 0x55) {
                    buffer = ByteArrayBuilder()
                    buffer.write(0x55)
                    buffer.write(0xAA)
                    state = State.COLLECTING
                }
                oldC = byte
            }
        }

        return false
    }
}

/**
 * Simple ByteArrayOutputStream replacement for KMP.
 */
class ByteArrayBuilder {
    private val data = mutableListOf<Byte>()

    fun write(b: Int) {
        data.add(b.toByte())
    }

    fun size(): Int = data.size

    fun toByteArray(): ByteArray = data.toByteArray()
}
