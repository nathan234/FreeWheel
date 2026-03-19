package org.freewheel.core.replay

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ReplayState {
    IDLE, LOADED, PLAYING, PAUSED, FINISHED
}

data class ReplayPosition(
    val currentTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val progress: Float = 0f,
    val packetIndex: Int = 0,
    val totalPackets: Int = 0
)

/**
 * Replays BLE capture files through real protocol decoders.
 *
 * Follows the [DemoDataProvider] pattern: standalone class, exposes [StateFlow<WheelState>],
 * merged via combine() in the ViewModel.
 *
 * Packets are fed through the actual decoder for the captured wheel type, exercising
 * the full state machine (frame reassembly, unpackers, checksums).
 */
class ReplayEngine(
    private val decoderFactory: WheelDecoderFactory = DefaultWheelDecoderFactory()
) {

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    private val _identityState = MutableStateFlow(WheelIdentity())
    val identityState: StateFlow<WheelIdentity> = _identityState.asStateFlow()

    private val _bmsState = MutableStateFlow(BmsState())
    val bmsState: StateFlow<BmsState> = _bmsState.asStateFlow()

    private val _settingsState = MutableStateFlow<WheelSettings>(WheelSettings.None)
    val settingsState: StateFlow<WheelSettings> = _settingsState.asStateFlow()

    private val _replayState = MutableStateFlow(ReplayState.IDLE)
    val replayState: StateFlow<ReplayState> = _replayState.asStateFlow()

    private val _position = MutableStateFlow(ReplayPosition())
    val position: StateFlow<ReplayPosition> = _position.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _captureHeader = MutableStateFlow<CaptureHeader?>(null)
    val captureHeader: StateFlow<CaptureHeader?> = _captureHeader.asStateFlow()

    private var decoder: WheelDecoder? = null
    private var config = DecoderConfig()
    private var rxPackets: List<CapturedPacket> = emptyList()
    private var capture: CaptureFile? = null
    private var job: Job? = null
    private var currentIndex: Int = 0

    /**
     * Load a capture file for replay. Creates the appropriate decoder based on wheel type.
     * @return true if the capture was loaded successfully, false if the wheel type is unsupported.
     */
    fun load(capture: CaptureFile): Boolean {
        stop()

        val newDecoder = decoderFactory.createDecoder(capture.header.wheelType) ?: return false

        this.capture = capture
        this.decoder = newDecoder
        this.rxPackets = capture.entries
            .filterIsInstance<CaptureEntry.Packet>()
            .map { it.packet }
            .filter { it.direction == BlePacketDirection.RX }

        _captureHeader.value = capture.header
        _telemetryState.value = TelemetryState()
        _identityState.value = WheelIdentity()
        _bmsState.value = BmsState()
        _settingsState.value = WheelSettings.None
        _position.value = ReplayPosition(
            totalDurationMs = capture.durationMs,
            totalPackets = rxPackets.size
        )
        _replayState.value = ReplayState.LOADED
        currentIndex = 0

        return true
    }

    /**
     * Start or restart playback from the current position.
     */
    fun start(scope: CoroutineScope) {
        if (_replayState.value != ReplayState.LOADED && _replayState.value != ReplayState.PAUSED) return

        _replayState.value = ReplayState.PLAYING
        job = scope.launch {
            playFromIndex(currentIndex)
        }
    }

    /**
     * Pause playback, preserving position.
     */
    fun pause() {
        if (_replayState.value != ReplayState.PLAYING) return
        job?.cancel()
        job = null
        _replayState.value = ReplayState.PAUSED
    }

    /**
     * Resume playback from the paused position.
     */
    fun resume(scope: CoroutineScope) {
        if (_replayState.value != ReplayState.PAUSED) return
        start(scope)
    }

    /**
     * Stop playback and reset to IDLE.
     */
    fun stop() {
        job?.cancel()
        job = null
        decoder?.reset()
        decoder = null
        capture = null
        rxPackets = emptyList()
        currentIndex = 0
        _replayState.value = ReplayState.IDLE
        _telemetryState.value = TelemetryState()
        _identityState.value = WheelIdentity()
        _bmsState.value = BmsState()
        _settingsState.value = WheelSettings.None
        _position.value = ReplayPosition()
        _captureHeader.value = null
    }

    /**
     * Set the playback speed multiplier (e.g., 0.5, 1.0, 2.0, 4.0).
     */
    fun setSpeed(multiplier: Float) {
        _speed.value = multiplier.coerceIn(0.1f, 10f)
    }

    /**
     * Seek to a position in the capture (0.0 = start, 1.0 = end).
     * Resets the decoder and fast-forwards through all packets up to the target.
     */
    fun seekTo(progress: Float, scope: CoroutineScope) {
        val packets = rxPackets
        if (packets.isEmpty()) return

        val wasPlaying = _replayState.value == ReplayState.PLAYING
        job?.cancel()
        job = null

        val targetIndex = (progress.coerceIn(0f, 1f) * packets.size).toInt()
            .coerceAtMost(packets.size - 1)

        // Reset decoder and fast-forward (no delays)
        val dec = decoderFactory.createDecoder(capture?.header?.wheelType ?: return) ?: return
        decoder = dec

        var tel = TelemetryState()
        var id = WheelIdentity()
        var bms = BmsState()
        var settings: WheelSettings = WheelSettings.None
        for (i in 0 until targetIndex) {
            val ds = DecoderState(tel, id, bms, settings)
            val result = dec.decode(packets[i].data, ds, config)
            if (result is DecodeResult.Success) {
                val d = result.data
                d.telemetry?.let { tel = it }
                d.identity?.let { id = it }
                d.bms?.let { bms = it }
                d.settings?.let { settings = it }
            }
        }

        _telemetryState.value = tel
        _identityState.value = id
        _bmsState.value = bms
        _settingsState.value = settings
        currentIndex = targetIndex
        updatePosition(targetIndex)

        if (wasPlaying) {
            _replayState.value = ReplayState.PLAYING
            job = scope.launch {
                playFromIndex(targetIndex)
            }
        } else {
            _replayState.value = ReplayState.PAUSED
        }
    }

    private suspend fun CoroutineScope.playFromIndex(startIndex: Int) {
        val packets = rxPackets
        val dec = decoder ?: return

        if (packets.isEmpty()) {
            _replayState.value = ReplayState.FINISHED
            return
        }

        var tel = _telemetryState.value
        var id = _identityState.value
        var bms = _bmsState.value
        var settings = _settingsState.value

        for (i in startIndex until packets.size) {
            if (!isActive) {
                currentIndex = i
                return
            }

            // Delay based on timestamp delta
            if (i > startIndex) {
                val deltaMs = packets[i].timestampMs - packets[i - 1].timestampMs
                if (deltaMs > 0) {
                    delay((deltaMs / _speed.value).toLong())
                }
            } else if (i > 0) {
                // Resuming from a paused position — delay from previous packet
                val deltaMs = packets[i].timestampMs - packets[i - 1].timestampMs
                if (deltaMs > 0) {
                    delay((deltaMs / _speed.value).toLong())
                }
            }

            if (!isActive) {
                currentIndex = i
                return
            }

            val ds = DecoderState(tel, id, bms, settings)
            val result = dec.decode(packets[i].data, ds, config)
            if (result is DecodeResult.Success) {
                val d = result.data
                d.telemetry?.let { tel = it; _telemetryState.value = it }
                d.identity?.let { id = it; _identityState.value = it }
                d.bms?.let { bms = it; _bmsState.value = it }
                d.settings?.let { settings = it; _settingsState.value = it }
                // Commands are intentionally discarded — no BLE to send to during replay
            }

            currentIndex = i + 1
            updatePosition(i + 1)
        }

        _replayState.value = ReplayState.FINISHED
    }

    private fun updatePosition(index: Int) {
        val packets = rxPackets
        val total = packets.size
        val cap = capture ?: return

        val currentTimeMs = if (index > 0 && index <= packets.size) {
            packets[index - 1].timestampMs - (packets.firstOrNull()?.timestampMs ?: 0L)
        } else {
            0L
        }

        _position.value = ReplayPosition(
            currentTimeMs = currentTimeMs,
            totalDurationMs = cap.durationMs,
            progress = if (total > 0) index.toFloat() / total else 0f,
            packetIndex = index,
            totalPackets = total
        )
    }
}
