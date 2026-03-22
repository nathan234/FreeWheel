package org.freewheel.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        val state = DecoderState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(1)))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, s ->
            FrameOutcome.Processed(FrameResult(telemetry = s.telemetry))
        }

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertNull(decoded.telemetry)
        assertEquals(false, decoded.hasNewData)
    }

    @Test
    fun noFrameAssembled_returnsBuffering() {
        // Unpacker never returns true, processFrame is never called.
        val state = DecoderState()
        val unpacker = FakeUnpacker() // no frames configured
        var processFrameCalled = false

        val result = decodeFrames(byteArrayOf(0x01, 0x02, 0x03), unpacker, state) { _, _ ->
            processFrameCalled = true
            FrameOutcome.Processed(FrameResult(telemetry = state.telemetry))
        }

        assertTrue(result is DecodeResult.Buffering)
        assertEquals(false, processFrameCalled)
    }

    @Test
    fun hasNewDataUsesOrSemantics() {
        // Two frames: first has hasNewData=true, second has hasNewData=false.
        // Result should have hasNewData=true (sticky OR).
        val state = DecoderState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 1 to frame2))
        var callCount = 0

        val result = decodeFrames(byteArrayOf(0x01, 0x02), unpacker, state) { _, s ->
            callCount++
            if (callCount == 1) {
                FrameOutcome.Processed(FrameResult(telemetry = s.telemetry, hasNewData = true))
            } else {
                FrameOutcome.Processed(FrameResult(telemetry = s.telemetry, hasNewData = false))
            }
        }

        assertTrue(result is DecodeResult.Success)
        assertTrue((result as DecodeResult.Success).data.hasNewData)
    }

    @Test
    fun commandsAccumulateAcrossFrames() {
        // Two frames each return a command. Result should contain both.
        val state = DecoderState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 1 to frame2))
        val cmd1 = WheelCommand.Beep
        val cmd2 = WheelCommand.PowerOff
        var callCount = 0

        val result = decodeFrames(byteArrayOf(0x01, 0x02), unpacker, state) { _, s ->
            callCount++
            if (callCount == 1) {
                FrameOutcome.Processed(FrameResult(telemetry = s.telemetry, commands = listOf(cmd1)))
            } else {
                FrameOutcome.Processed(FrameResult(telemetry = s.telemetry, commands = listOf(cmd2)))
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
        val state = DecoderState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 2 to frame2))

        // 3 bytes: frame at byte 0, nothing at byte 1, frame at byte 2
        decodeFrames(byteArrayOf(0x01, 0x02, 0x03), unpacker, state) { _, s ->
            FrameOutcome.Processed(FrameResult(telemetry = s.telemetry))
        }

        // reset() should have been called twice — once after each frame extraction
        assertEquals(2, unpacker.resetCount)
    }

    @Test
    fun framesExtractedButAllUnrecognized_returnsUnhandled() {
        // Unpacker yields a complete frame but processFrame returns Unrecognized.
        // Result should be Unhandled.
        val state = DecoderState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(0xFF.toByte())))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, _ ->
            FrameOutcome.Unrecognized("test")
        }

        assertTrue(result is DecodeResult.Unhandled)
        val unhandled = result as DecodeResult.Unhandled
        assertEquals("test FF", unhandled.reason.detail, "detail should contain hint and hex dump of unhandled buffer")
    }

    @Test
    fun processedWithNoNewData_returnsSuccess_notUnhandled() {
        // Regression test: a recognized frame that produces no new data
        // (e.g., BMS cells) must return Success, not Unhandled.
        val state = DecoderState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(0xAB.toByte())))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, _ ->
            FrameOutcome.Processed(FrameResult(hasNewData = false))
        }

        assertTrue(result is DecodeResult.Success)
        assertEquals(false, (result as DecodeResult.Success).data.hasNewData)
    }

    @Test
    fun mixOfProcessedAndUnrecognized_returnsSuccess() {
        // If any frame is Processed, the overall result is Success
        // even if other frames in the same notification are Unrecognized.
        val state = DecoderState()
        val frame1 = byteArrayOf(1)
        val frame2 = byteArrayOf(2)
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to frame1, 1 to frame2))
        var callCount = 0

        val result = decodeFrames(byteArrayOf(0x01, 0x02), unpacker, state) { _, _ ->
            callCount++
            if (callCount == 1) {
                FrameOutcome.Unrecognized("unknown_type")
            } else {
                FrameOutcome.Processed(FrameResult(hasNewData = true))
            }
        }

        assertTrue(result is DecodeResult.Success)
        assertTrue((result as DecodeResult.Success).data.hasNewData)
    }

    @Test
    fun unrecognizedHintIncludedInUnhandledReason() {
        // The hint from Unrecognized flows through to the UnhandledReason detail.
        val state = DecoderState()
        val unpacker = FakeUnpacker(frameAtByte = mapOf(0 to byteArrayOf(0x05)))

        val result = decodeFrames(byteArrayOf(0x42), unpacker, state) { _, _ ->
            FrameOutcome.Unrecognized("type=0x05")
        }

        assertTrue(result is DecodeResult.Unhandled)
        val detail = (result as DecodeResult.Unhandled).reason.detail
        assertTrue(detail.startsWith("type=0x05"), "hint should be at start of detail, got: $detail")
        assertTrue(detail.contains("05"), "frame hex should be in detail, got: $detail")
    }
}
