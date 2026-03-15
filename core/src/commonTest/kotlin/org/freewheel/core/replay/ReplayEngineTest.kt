package org.freewheel.core.replay

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecodedData
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.domain.CapabilitySet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayEngineTest {

    // A simple test decoder that extracts speed from the first 2 bytes (big-endian)
    private class TestDecoder : WheelDecoder {
        override val wheelType = WheelType.KINGSONG
        private var ready = false

        override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
            if (data.size < 2) return null
            val speed = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            ready = true
            return DecodedData(
                newState = currentState.copy(speed = speed),
                commands = listOf(WheelCommand.Beep) // Commands should be discarded
            )
        }

        override fun isReady(): Boolean = ready
        override fun reset() { ready = false }
    }

    private class TestDecoderFactory : WheelDecoderFactory {
        var lastCreatedType: WheelType? = null

        override fun createDecoder(wheelType: WheelType): WheelDecoder? {
            lastCreatedType = wheelType
            return if (wheelType == WheelType.Unknown) null else TestDecoder()
        }

        override fun supportedTypes() = listOf(WheelType.KINGSONG)
    }

    private fun makeCapture(
        wheelType: WheelType = WheelType.KINGSONG,
        packets: List<CapturedPacket> = emptyList(),
        markers: List<CapturedMarker> = emptyList()
    ): CaptureFile {
        val entries = mutableListOf<CaptureEntry>()
        packets.forEach { entries.add(CaptureEntry.Packet(it)) }
        markers.forEach { entries.add(CaptureEntry.Marker(it)) }
        entries.sortBy { it.timestampMs }

        val duration = if (entries.size >= 2) {
            entries.last().timestampMs - entries.first().timestampMs
        } else 0L

        return CaptureFile(
            header = CaptureHeader(wheelType, wheelType.name, "TestWheel", "1.0", "1.0"),
            entries = entries,
            durationMs = duration
        )
    }

    private fun rxPacket(timestampMs: Long, speed: Int): CapturedPacket {
        return CapturedPacket(
            timestampMs = timestampMs,
            direction = BlePacketDirection.RX,
            data = byteArrayOf((speed shr 8).toByte(), (speed and 0xFF).toByte())
        )
    }

    private fun txPacket(timestampMs: Long): CapturedPacket {
        return CapturedPacket(
            timestampMs = timestampMs,
            direction = BlePacketDirection.TX,
            data = byteArrayOf(0x01, 0x02)
        )
    }

    @Test
    fun loadCreatesCorrectDecoderType() {
        val factory = TestDecoderFactory()
        val engine = ReplayEngine(factory)

        val capture = makeCapture(WheelType.KINGSONG, listOf(rxPacket(1000, 100)))
        assertTrue(engine.load(capture))
        assertEquals(WheelType.KINGSONG, factory.lastCreatedType)
        assertEquals(ReplayState.LOADED, engine.replayState.value)
        assertNotNull(engine.captureHeader.value)
    }

    @Test
    fun unknownWheelTypeReturnsFalse() {
        val factory = TestDecoderFactory()
        val engine = ReplayEngine(factory)

        val capture = makeCapture(WheelType.Unknown, listOf(rxPacket(1000, 100)))
        assertFalse(engine.load(capture))
        assertEquals(ReplayState.IDLE, engine.replayState.value)
    }

    @Test
    fun playingEmitsWheelStateUpdates() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 500),
            rxPacket(1100, 1000),
            rxPacket(1200, 1500)
        ))
        engine.load(capture)
        engine.start(this)

        advanceUntilIdle()

        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(1500, engine.wheelState.value.speed)
    }

    @Test
    fun pauseResumePreservesPosition() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(0, 100),
            rxPacket(100, 200),
            rxPacket(5000, 300), // large gap — enough to pause during
            rxPacket(5100, 400)
        ))
        engine.load(capture)
        engine.start(this)

        // Process first two packets, then the 5-second gap begins
        advanceTimeBy(150)

        engine.pause()
        assertEquals(ReplayState.PAUSED, engine.replayState.value)
        // Should have processed at least the first two packets
        assertTrue(engine.wheelState.value.speed >= 200)
        val pausedSpeed = engine.wheelState.value.speed

        engine.resume(this)
        assertEquals(ReplayState.PLAYING, engine.replayState.value)

        advanceUntilIdle()
        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(400, engine.wheelState.value.speed)
    }

    @Test
    fun speedMultiplierAffectsTiming() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(0, 100),
            rxPacket(1000, 200) // 1 second gap
        ))
        engine.load(capture)
        engine.setSpeed(2f) // 2x speed = 500ms delay instead of 1000ms
        engine.start(this)

        // At 600ms with 2x speed, the 1000ms gap is halved to 500ms — should be done
        advanceTimeBy(600)
        assertEquals(200, engine.wheelState.value.speed)
    }

    @Test
    fun txPacketsSkippedInDecoding() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 500),
            txPacket(1050),  // TX packet — should be filtered out
            rxPacket(1100, 1000)
        ))
        engine.load(capture)

        // Position should only count RX packets
        assertEquals(2, engine.position.value.totalPackets)

        engine.start(this)
        advanceUntilIdle()

        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(1000, engine.wheelState.value.speed)
    }

    @Test
    fun stopResetsToIdle() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(rxPacket(1000, 500)))
        engine.load(capture)
        assertEquals(ReplayState.LOADED, engine.replayState.value)

        engine.stop()
        assertEquals(ReplayState.IDLE, engine.replayState.value)
        assertEquals(0, engine.wheelState.value.speed)
        assertEquals(0, engine.position.value.totalPackets)
    }

    @Test
    fun emptyRxListFinishesImmediately() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        // Only TX packets — no RX to replay
        val capture = makeCapture(packets = listOf(txPacket(1000), txPacket(1100)))
        engine.load(capture)
        assertEquals(0, engine.position.value.totalPackets)

        engine.start(this)
        advanceUntilIdle()

        assertEquals(ReplayState.FINISHED, engine.replayState.value)
    }

    @Test
    fun seekResetsDecoderAndFastForwards() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(0, 100),
            rxPacket(100, 200),
            rxPacket(200, 300),
            rxPacket(300, 400)
        ))
        engine.load(capture)

        // Seek to 75% = index 3 of 4 packets
        engine.seekTo(0.75f, this)

        // Should have fast-forwarded through packets 0, 1, 2 (indices 0..2)
        // The decoder saw packets with speeds 100, 200, 300 — state should be 300
        assertEquals(300, engine.wheelState.value.speed)
        assertEquals(ReplayState.PAUSED, engine.replayState.value)
    }

    @Test
    fun seekDuringPlaybackContinuesPlaying() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(0, 100),
            rxPacket(100, 200),
            rxPacket(200, 300),
            rxPacket(300, 400)
        ))
        engine.load(capture)
        engine.start(this)

        // Let it play a bit
        advanceTimeBy(50)

        // Seek to near the end
        engine.seekTo(0.75f, this)

        advanceUntilIdle()

        // Should finish playing the remaining packets
        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(400, engine.wheelState.value.speed)
    }

    @Test
    fun positionUpdatesCorrectly() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(packets = listOf(
            rxPacket(1000, 100),
            rxPacket(2000, 200),
            rxPacket(3000, 300)
        ))
        engine.load(capture)

        assertEquals(0, engine.position.value.packetIndex)
        assertEquals(3, engine.position.value.totalPackets)
        assertEquals(2000L, engine.position.value.totalDurationMs)

        engine.start(this)
        advanceUntilIdle()

        assertEquals(3, engine.position.value.packetIndex)
        assertEquals(1f, engine.position.value.progress)
    }

    @Test
    fun markersDoNotAffectRxPacketList() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture = makeCapture(
            packets = listOf(
                rxPacket(1000, 100),
                rxPacket(2000, 200)
            ),
            markers = listOf(
                CapturedMarker(1500, "test marker")
            )
        )
        engine.load(capture)

        // Only 2 RX packets, marker is ignored
        assertEquals(2, engine.position.value.totalPackets)

        engine.start(this)
        advanceUntilIdle()

        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(200, engine.wheelState.value.speed)
    }

    @Test
    fun loadAfterPlaybackResets() = runTest {
        val engine = ReplayEngine(TestDecoderFactory())

        val capture1 = makeCapture(packets = listOf(rxPacket(1000, 500)))
        engine.load(capture1)
        engine.start(this)
        advanceUntilIdle()
        assertEquals(ReplayState.FINISHED, engine.replayState.value)
        assertEquals(500, engine.wheelState.value.speed)

        // Load a new capture
        val capture2 = makeCapture(packets = listOf(rxPacket(2000, 999)))
        engine.load(capture2)
        assertEquals(ReplayState.LOADED, engine.replayState.value)
        assertEquals(0, engine.wheelState.value.speed) // Reset
    }
}
