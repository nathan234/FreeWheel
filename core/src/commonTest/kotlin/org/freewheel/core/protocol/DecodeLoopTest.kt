package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the shared [decodeFrames] function.
 *
 * Uses a [FakeUnpacker] that returns pre-configured frames to test the
 * loop contract independently of any real protocol.
 */
class DecodeLoopTest {

    /**
     * Fake unpacker that yields pre-configured frames at specified byte positions.
     *
     * Each entry in [frameAtByte] maps a byte index (0-based count of addChar calls)
     * to the frame buffer that should be returned at that point.
     */
    private class FakeUnpacker(
        private val frameAtByte: Map<Int, ByteArray> = emptyMap()
    ) : Unpacker {
        private var byteCount = 0
        private var currentBuffer: ByteArray = ByteArray(0)
        var resetCount = 0
            private set

        override fun addChar(c: Int): Boolean {
            val index = byteCount++
            val frame = frameAtByte[index]
            if (frame != null) {
                currentBuffer = frame
                return true
            }
            return false
        }

        override fun getBuffer(): ByteArray = currentBuffer

        override fun reset() {
            resetCount++
            currentBuffer = ByteArray(0)
        }
    }

    @Test
    fun frameProcessedButStateUnchanged_returnsSuccess() {
        // A frame is processed but processFrame returns the same state unchanged.
        // Result should be Success (frame was processed).
        val state = WheelState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(1)))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, s ->
            FrameResult(s) // same state, no new data
        }

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(state, decoded.newState!!)
        assertEquals(false, decoded.hasNewData)
    }

    @Test
    fun noFrameAssembled_returnsBuffering() {
        // Unpacker never returns true, processFrame is never called.
        val state = WheelState()
        val unpacker = FakeUnpacker() // no frames configured
        var processFrameCalled = false

        val result = decodeFrames(byteArrayOf(0x01, 0x02, 0x03), unpacker, state) { _, _ ->
            processFrameCalled = true
            FrameResult(state)
        }

        assertTrue(result is DecodeResult.Buffering)
        assertEquals(false, processFrameCalled)
    }

    @Test
    fun hasNewDataUsesOrSemantics() {
        // Two frames: first has hasNewData=true, second has hasNewData=false.
        // Result should have hasNewData=true (sticky OR).
        val state = WheelState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 1 to frame2))
        var callCount = 0

        val result = decodeFrames(byteArrayOf(0x01, 0x02), unpacker, state) { _, s ->
            callCount++
            if (callCount == 1) {
                FrameResult(s, hasNewData = true)
            } else {
                FrameResult(s, hasNewData = false)
            }
        }

        assertTrue(result is DecodeResult.Success)
        assertTrue((result as DecodeResult.Success).data.hasNewData)
    }

    @Test
    fun commandsAccumulateAcrossFrames() {
        // Two frames each return a command. Result should contain both.
        val state = WheelState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 1 to frame2))
        val cmd1 = WheelCommand.Beep
        val cmd2 = WheelCommand.PowerOff
        var callCount = 0

        val result = decodeFrames(byteArrayOf(0x01, 0x02), unpacker, state) { _, s ->
            callCount++
            if (callCount == 1) {
                FrameResult(s, commands = listOf(cmd1))
            } else {
                FrameResult(s, commands = listOf(cmd2))
            }
        }

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(2, decoded.commands.size)
        assertEquals(cmd1, decoded.commands[0])
        assertEquals(cmd2, decoded.commands[1])
    }

    @Test
    fun unpackerResetBetweenFrames() {
        // Verify reset() is called after each frame extraction, enabling
        // the unpacker to detect the next frame in the same BLE notification.
        val state = WheelState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 2 to frame2))

        // 3 bytes: frame at byte 0, nothing at byte 1, frame at byte 2
        decodeFrames(byteArrayOf(0x01, 0x02, 0x03), unpacker, state) { _, s ->
            FrameResult(s)
        }

        // reset() should have been called twice — once after each frame extraction
        assertEquals(2, unpacker.resetCount)
    }

    @Test
    fun framesExtractedButAllUnrecognized_returnsUnhandled() {
        // Unpacker yields a complete frame but processFrame returns null
        // for all frames. Result should be Unhandled.
        val state = WheelState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(0xFF.toByte())))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, _ ->
            null // unrecognized frame
        }

        assertTrue(result is DecodeResult.Unhandled)
    }
}
