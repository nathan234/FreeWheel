package org.freewheel.core.protocol

/**
 * Interface for frame unpackers that reassemble BLE notifications into complete protocol frames.
 *
 * All unpackers follow a state machine pattern:
 * 1. Feed bytes one at a time via [addChar]
 * 2. When a complete frame is detected, [addChar] returns true
 * 3. Retrieve the frame via [getBuffer]
 * 4. Call [reset] to prepare for the next frame
 */
internal interface Unpacker {
    /**
     * Add a byte to the unpacker.
     * @param c The byte value (0-255)
     * @return true if a complete valid frame is ready for retrieval via [getBuffer]
     */
    fun addChar(c: Int): Boolean

    /**
     * Get the complete frame buffer.
     * Only valid after [addChar] returns true.
     */
    fun getBuffer(): ByteArray

    /**
     * Reset the unpacker state, ready to receive a new frame.
     */
    fun reset()
}
