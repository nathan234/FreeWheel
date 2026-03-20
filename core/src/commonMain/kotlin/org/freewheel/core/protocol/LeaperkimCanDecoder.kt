package org.freewheel.core.protocol

import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.ByteUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Leaperkim CAN-over-BLE protocol decoder.
 *
 * Used by newer Leaperkim-manufactured wheels that use the CAN protocol instead of
 * the legacy DC 5A 5C streaming protocol handled by [VeteranDecoder].
 *
 * Frame format (handled by internal unpacker):
 * ```
 * AA AA [CAN_ID 4B LE] [reserved 10B] [payload] [checksum 1B] 55 55
 * ```
 * - Minimum frame size: 21 bytes (header 2 + CAN ID 4 + reserved 10 + checksum 1 + trailer 2 + min 2 payload)
 * - Escape: `0xA5` bytes are doubled in transit, deduplicated on receive
 * - Checksum: sum of bytes from offset 2 to (length-3), mod 256
 *
 * State machine (inside [decode]):
 * ```
 * PASSWORD -> (ACK) -> INIT_COMM -> (ACK) -> INIT_STATUS -> (response) -> POLLING
 *                                                                           |
 *                                                          READ_VALUES <-> READ_STATUS
 * ```
 *
 * Protocol details reverse-engineered from EUC World's Veteran/Leaperkim decoder.
 *
 * Thread-safe: All methods except [buildCommand] are called from the WCM
 * event loop (single-threaded). [buildCommand] does not read any mutable
 * internal state, so no lock is needed.
 */
class LeaperkimCanDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.LEAPERKIM

    // ==================== CAN Message IDs ====================

    internal companion object {
        val SUPPORTED_COMMANDS: Set<SettingsCommandId> = setOf(
            SettingsCommandId.LIGHT_MODE,
            SettingsCommandId.LED,
            SettingsCommandId.HANDLE_BUTTON,
            SettingsCommandId.RIDE_MODE,
            SettingsCommandId.MAX_SPEED,
            SettingsCommandId.PEDAL_TILT,
            SettingsCommandId.PEDAL_SENSITIVITY,
            SettingsCommandId.SPEAKER_VOLUME,
            SettingsCommandId.LOCK,
            SettingsCommandId.POWER_OFF,
        )

        // TX commands
        const val CAN_INIT_PASSWORD = 0x0F58B927
        const val CAN_INIT_COMM = 0x0F022A02
        const val CAN_INIT_STATUS = 0x0F58B704
        const val CAN_READ_VALUES = 0x0F58B703
        const val CAN_READ_STATUS = 0x0F58B704
        const val CAN_WRITE_HEADLIGHT = 0x0F58B6FD
        const val CAN_WRITE_PEDAL_SPEED_RIDE = 0x0F58B705
        const val CAN_WRITE_MULTI = 0x0F58B706
        const val CAN_WRITE_HANDLE_BUTTON = 0x0F58B71E
        const val CAN_WRITE_SOUND = 0x0F58D309
        const val CAN_WRITE_SPEAKER_VOLUME = 0x0F58D30A

        // Frame constants
        const val HEADER_BYTE = 0xAA
        const val TRAILER_BYTE = 0x55
        const val ESCAPE_BYTE = 0xA5

        // Multi-purpose command byte (CAN_WRITE_MULTI byte 0)
        const val MULTI_CMD_BYTE = 0xB2

        // Multi-purpose sub-commands (CAN_WRITE_MULTI byte 4)
        const val MULTI_LOCK_ON = 3
        const val MULTI_LOCK_OFF = 4
        const val MULTI_POWER_OFF = 5
        const val MULTI_HORN = 0x11
        const val MULTI_LED_ON = 15
        const val MULTI_LED_OFF = 16
        const val MULTI_TRANSPORT_ON = 18
        const val MULTI_TRANSPORT_OFF = 19

        // Fixed payload size for commands
        const val CMD_PAYLOAD_SIZE = 8

        // Reserved area size in frame (between CAN ID and payload)
        const val RESERVED_SIZE = 10

        // Default password
        const val DEFAULT_PASSWORD = "000000"

        // Keep-alive interval
        const val KEEP_ALIVE_INTERVAL_MS = 500L

        // Map CAN IDs to frame type labels for the polling phase
        private val CAN_ID_FRAME_TYPES = mapOf(
            CAN_READ_VALUES to "TELEMETRY",
            CAN_READ_STATUS to "STATUS",
            CAN_INIT_STATUS to "STATUS"
        )
    }

    // ==================== Internal State ====================

    private enum class InitPhase {
        PASSWORD,
        INIT_COMM,
        INIT_STATUS,
        POLLING
    }

    private var phase = InitPhase.PASSWORD
    private var modelId = -1
    private var alternateKeepAlive = false

    // ==================== Internal Unpacker ====================

    /**
     * CAN frame unpacker. Handles AA AA header detection, 0xA5 escape deduplication,
     * checksum verification, and 55 55 trailer detection.
     */
    private class CanUnpacker : Unpacker {
        private enum class State {
            WAIT_HEADER_1,
            WAIT_HEADER_2,
            COLLECTING,
            ESCAPE
        }

        private val buffer = ByteArrayBuilder()
        private var state = State.WAIT_HEADER_1
        private var frameComplete = false

        override fun addChar(c: Int): Boolean {
            val byte = c and 0xFF

            when (state) {
                State.WAIT_HEADER_1 -> {
                    if (byte == HEADER_BYTE) {
                        state = State.WAIT_HEADER_2
                    }
                }

                State.WAIT_HEADER_2 -> {
                    if (byte == HEADER_BYTE) {
                        buffer.clear()
                        buffer.write(HEADER_BYTE)
                        buffer.write(HEADER_BYTE)
                        state = State.COLLECTING
                    } else {
                        state = State.WAIT_HEADER_1
                    }
                }

                State.COLLECTING -> {
                    if (byte == ESCAPE_BYTE) {
                        state = State.ESCAPE
                    } else if (byte == TRAILER_BYTE && buffer.size() >= 2 &&
                        (buffer[buffer.size() - 1].toInt() and 0xFF) == TRAILER_BYTE
                    ) {
                        // Found 55 55 trailer — the first 55 was already added to buffer.
                        // The frame is: [AA AA] [data...] [checksum] [55 55]
                        // We keep everything in the buffer including the trailing 55 55.
                        buffer.write(TRAILER_BYTE)
                        frameComplete = true
                        state = State.WAIT_HEADER_1
                        return true
                    } else {
                        buffer.write(byte)
                    }
                }

                State.ESCAPE -> {
                    // After 0xA5, the next byte is the actual data (duplicated 0xA5 → single 0xA5)
                    buffer.write(byte)
                    state = State.COLLECTING
                }
            }

            return false
        }

        override fun getBuffer(): ByteArray = buffer.toByteArray()

        override fun reset() {
            buffer.clear()
            state = State.WAIT_HEADER_1
            frameComplete = false
        }
    }

    private val unpacker = CanUnpacker()

    // ==================== WheelDecoder Interface ====================

    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
        val loopResult = decodeFrames(data, unpacker, currentState) { buffer, state ->
            processFrame(buffer, state, config)
        }

        return when (loopResult) {
            is DecodeResult.Success -> {
                // Ensure wheelType is LEAPERKIM
                val resolvedIdentity = when {
                    loopResult.data.identity != null && loopResult.data.identity.wheelType == WheelType.Unknown ->
                        loopResult.data.identity.copy(wheelType = WheelType.LEAPERKIM)
                    loopResult.data.identity != null -> loopResult.data.identity
                    currentState.identity.wheelType == WheelType.Unknown ->
                        currentState.identity.copy(wheelType = WheelType.LEAPERKIM)
                    else -> null
                }
                DecodeResult.Success(DecodedData(
                    telemetry = loopResult.data.telemetry,
                    identity = resolvedIdentity?.takeIf { it != currentState.identity },
                    bms = loopResult.data.bms,
                    settings = loopResult.data.settings?.takeIf { it != currentState.settings },
                    commands = loopResult.data.commands,
                    hasNewData = loopResult.data.hasNewData,
                    news = loopResult.data.news,
                    frameTypes = loopResult.data.frameTypes
                ))
            }
            is DecodeResult.Buffering -> loopResult
            is DecodeResult.Unhandled -> loopResult
        }
    }

    private fun processFrame(
        frame: ByteArray,
        currentState: DecoderState,
        config: DecoderConfig
    ): FrameResult? {
        // Minimum frame: AA AA [4B CAN ID] [10B reserved] [payload...] [1B checksum] 55 55
        // That's 2 + 4 + 10 + payload + 1 + 2 = 19 + payload bytes minimum
        if (frame.size < 19) return null

        // Verify checksum: sum of bytes from offset 2 to (length-3), mod 256
        val checksumEnd = frame.size - 3 // last byte before 55 55
        val expectedChecksum = frame[checksumEnd].toInt() and 0xFF
        var checksum = 0
        for (i in 2 until checksumEnd) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        if (checksum != expectedChecksum) return null

        // Extract CAN ID (4 bytes LE starting at offset 2)
        val canId = ByteUtils.intFromBytesLE(frame, 2)

        // Payload starts after header (2) + CAN ID (4) + reserved (10) = offset 16
        val payloadStart = 16
        val payloadEnd = frame.size - 3 // before checksum + trailer
        if (payloadEnd <= payloadStart) return null
        val payload = frame.copyOfRange(payloadStart, payloadEnd)

        return when (phase) {
            InitPhase.PASSWORD -> {
                // Any response after password → send INIT_COMM
                phase = InitPhase.INIT_COMM
                val cmd = buildCanFrame(CAN_INIT_COMM, ByteArray(CMD_PAYLOAD_SIZE))
                FrameResult(
                    commands = listOf(WheelCommand.SendBytes(cmd)),
                    frameType = "INIT_PASSWORD"
                )
            }

            InitPhase.INIT_COMM -> {
                // Any response after init comm → send INIT_STATUS request
                phase = InitPhase.INIT_STATUS
                val cmd = buildCanFrame(CAN_INIT_STATUS, ByteArray(CMD_PAYLOAD_SIZE))
                FrameResult(
                    commands = listOf(WheelCommand.SendBytes(cmd)),
                    frameType = "INIT_COMM"
                )
            }

            InitPhase.INIT_STATUS -> {
                // Status response → parse settings, enter polling
                phase = InitPhase.POLLING
                parseStatusResponse(payload, currentState).copy(hasNewData = true, frameType = "INIT_STATUS")
            }

            InitPhase.POLLING -> {
                val frameType = CAN_ID_FRAME_TYPES[canId] ?: "POLLING_UNKNOWN"
                if (canId == CAN_READ_VALUES) {
                    parseTelemetryResponse(payload, currentState).copy(hasNewData = true, frameType = frameType)
                } else if (canId == CAN_READ_STATUS || canId == CAN_INIT_STATUS) {
                    parseStatusResponse(payload, currentState).copy(hasNewData = true, frameType = frameType)
                } else {
                    // Unknown CAN ID — ignore
                    FrameResult(frameType = frameType)
                }
            }
        }
    }

    // ==================== Battery Calculation ====================

    /**
     * Calculate battery percentage from voltage using piecewise-linear interpolation.
     *
     * Currently all known CAN protocol models are treated as 100V class (24s).
     * This is a conservative default — the CAN protocol wheels are InMotion-manufactured
     * so their voltage classes may differ from legacy Veteran wheels. Having any battery %
     * is better than showing 0.
     *
     * @param voltage Voltage stored as ×100 (e.g. 8400 = 84.00V)
     * @return Battery percentage 0-100
     */
    private fun calculateBatteryPercent(voltage: Int): Int {
        return when {
            // 100V class (24s): Sherman, Abrams, R0, and other unknown models
            else -> when {
                voltage <= 7935 -> 0
                voltage >= 9870 -> 100
                else -> ((voltage - 7935) / 19.5).roundToInt()
            }
        }
    }

    // ==================== Telemetry Parsing ====================

    /**
     * Parse READ_VALUES response payload.
     *
     * All offsets are into the payload (after CAN header + reserved area).
     * All multi-byte values are little-endian.
     */
    private fun parseTelemetryResponse(payload: ByteArray, currentState: DecoderState): FrameResult {
        if (payload.size < 52) return FrameResult()

        // Pedal tilt: offset 0, 4B int32 LE, Q16.16 fixed point
        val rawTilt = ByteUtils.intFromBytesLE(payload, 0)
        val angle = rawTilt / 65536.0

        // Motor RPM 1: offset 12, 4B int32 LE
        val rpm1 = ByteUtils.intFromBytesLE(payload, 12)
        // Motor RPM 2: offset 16, 4B int32 LE
        val rpm2 = ByteUtils.intFromBytesLE(payload, 16)
        // Speed: abs(rpm1+rpm2) * 3.6 / 7624.0 * 100 → stored as ×100
        val speed = (abs(rpm1 + rpm2).toDouble() * 3.6 / 7624.0 * 100).roundToInt()

        // Distance: offset 20, 4B int32 LE, * 10 → meters
        val distance = ByteUtils.intFromBytesLE(payload, 20).toLong() * 10

        // Voltage: offset 24, 4B int32 LE signed, * 0.01 → volts
        // Store as ×100: value * 0.01 * 100 = value
        val voltage = ByteUtils.intFromBytesLE(payload, 24)

        // Battery level from voltage
        val battery = calculateBatteryPercent(voltage)

        // Temperature: offset 32, 1B uint8, * 100 → stored as ×100
        val temperature = if (payload.size > 32) {
            (payload[32].toInt() and 0xFF) * 100
        } else 0

        // Current: offset 48, 4B int32 LE signed, * 0.001 → amps
        // Store as ×100: value * 0.001 * 100 = value * 0.1
        val phaseCurrent = if (payload.size > 51) {
            (ByteUtils.intFromBytesLE(payload, 48) * 0.1).roundToInt()
        } else 0

        // Power: current_A * voltage_V → store as ×100
        // current_A = phaseCurrent / 100.0, voltage_V = voltage / 100.0
        // power_x100 = (phaseCurrent / 100.0) * (voltage / 100.0) * 100 = phaseCurrent * voltage / 10000.0 * 100
        val power = ((phaseCurrent.toDouble() / 100.0) * (voltage.toDouble() / 100.0) * 100).roundToInt()

        return FrameResult(
            telemetry = currentState.telemetry.copy(
                speed = speed,
                voltage = voltage,
                batteryLevel = battery,
                phaseCurrent = phaseCurrent,
                current = phaseCurrent,
                power = power,
                temperature = temperature,
                wheelDistance = distance,
                angle = angle
            ),
            identity = currentState.identity.copy(wheelType = WheelType.LEAPERKIM)
        )
    }

    // ==================== Status Parsing ====================

    /**
     * Parse INIT_STATUS / READ_STATUS response payload.
     *
     * All offsets are into the payload (after CAN header + reserved area).
     */
    private fun parseStatusResponse(payload: ByteArray, currentState: DecoderState): FrameResult {
        var id = currentState.identity
        val lk = currentState.settings as? WheelSettings.LeaperkimCan ?: WheelSettings.LeaperkimCan()
        var settings = lk

        // Serial: offset 0, 8B uint64 LE → hex string
        if (payload.size >= 8) {
            val serialLong = ByteUtils.longFromBytesLE(payload, 0)
            val serial = serialLong.toULong().toString(16).uppercase()
            id = id.copy(serialNumber = serial)
        }

        // Firmware: offset 24-27, 4 bytes → major.minor.patch
        if (payload.size >= 28) {
            val major = payload[24].toInt() and 0xFF
            val minor = payload[25].toInt() and 0xFF
            val patch = payload[26].toInt() and 0xFF
            id = id.copy(version = "$major.$minor.$patch")
        }

        // Pedal tilt: offset 56, 4B int32 LE / 65536.0 → degrees × 10 for state
        if (payload.size >= 60) {
            val rawTilt = ByteUtils.intFromBytesLE(payload, 56)
            val tiltDegrees = rawTilt / 65536.0
            settings = settings.copy(pedalTilt = (tiltDegrees * 10).roundToInt())
        }

        // Headlight: offset 80, 1B, bit 0
        if (payload.size >= 81) {
            val lightOn = (payload[80].toInt() and 0x01) != 0
            settings = settings.copy(lightMode = if (lightOn) 1 else 0)
        }

        // Model ID: offset 104-107, 4B
        if (payload.size >= 108) {
            modelId = ByteUtils.intFromBytesLE(payload, 104)
            id = id.copy(model = modelName(modelId))
        }

        // Pedal sensitivity: offset 124, 1B, (value - 64) * 1.5625
        if (payload.size >= 125) {
            val raw = payload[124].toInt() and 0xFF
            val sensitivity = ((raw - 64) * 1.5625).roundToInt()
            settings = settings.copy(pedalSensitivity = sensitivity)
        }

        // Riding mode: offset 125-126, 2B int16 LE / 100
        if (payload.size >= 127) {
            val rawMode = ByteUtils.signedShortFromBytesLE(payload, 125)
            settings = settings.copy(rideMode = rawMode != 0)
        }

        // Handle button: offset 129, 1B, bit 0
        if (payload.size >= 130) {
            val handleOn = (payload[129].toInt() and 0x01) != 0
            settings = settings.copy(handleButton = handleOn)
        }

        // LEDs: offset 130, 1B, bit 0
        if (payload.size >= 131) {
            val ledOn = (payload[130].toInt() and 0x01) != 0
            settings = settings.copy(ledMode = if (ledOn) 1 else 0)
        }

        // Transport mode: offset 132, 1B, bit 0
        if (payload.size >= 133) {
            val transportOn = (payload[132].toInt() and 0x01) != 0
            settings = settings.copy(transportMode = transportOn)
        }

        id = id.copy(wheelType = WheelType.LEAPERKIM)
        return FrameResult(identity = id, settings = settings)
    }

    // ==================== Model Name Lookup ====================

    /**
     * Look up model name from the model ID byte in the status response.
     */
    private fun modelName(id: Int): String {
        return when (id) {
            0 -> "Sherman"          // R1N
            1 -> "Sherman S"        // R1S
            2 -> "Sherman CF"       // R1CF
            3 -> "Sherman AP"       // R1AP
            4 -> "Sherman EX"       // R1EX
            6 -> "Sherman T"        // R1T
            7 -> "Sherman 10"       // R10
            10 -> "V3"
            11 -> "V3C"
            12 -> "V3 PRO"
            13 -> "V3S"
            20 -> "Abrams"          // R2
            21 -> "Abrams N"        // R2N
            22 -> "Abrams S"        // R2S
            24 -> "Abrams EX"       // R2EX
            30 -> "R0"
            50 -> "V5"
            51 -> "V5+"
            52 -> "V5F"
            53 -> "V5F+"
            60 -> "L6"
            61 -> "Lively"
            80 -> "V8"
            85 -> "Glide 3"
            86 -> "V8F"
            87 -> "V8S"
            100 -> "V10S"
            101 -> "V10SF"
            140 -> "V10"
            141 -> "V10F"
            142 -> "V10T"
            143 -> "V10FT"
            else -> "Leaperkim ($id)"
        }
    }

    // ==================== Frame Building ====================

    /**
     * Build a CAN frame with header, CAN ID, reserved area, payload, checksum, and trailer.
     * Applies 0xA5 byte-stuffing to the body (everything between header and trailer).
     */
    internal fun buildCanFrame(canId: Int, payload: ByteArray): ByteArray {
        // Build unescaped body: CAN_ID (4B LE) + reserved (10B zeros) + payload
        val body = ByteArray(4 + RESERVED_SIZE + payload.size)
        // CAN ID as little-endian
        body[0] = (canId and 0xFF).toByte()
        body[1] = ((canId shr 8) and 0xFF).toByte()
        body[2] = ((canId shr 16) and 0xFF).toByte()
        body[3] = ((canId shr 24) and 0xFF).toByte()
        // Reserved is already zeros from ByteArray init
        payload.copyInto(body, 4 + RESERVED_SIZE)

        // Calculate checksum over body
        var checksum = 0
        for (b in body) {
            checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF
        }

        // Build escaped output
        val result = mutableListOf<Byte>()
        result.add(HEADER_BYTE.toByte())
        result.add(HEADER_BYTE.toByte())

        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v == ESCAPE_BYTE) {
                result.add(ESCAPE_BYTE.toByte())
                result.add(ESCAPE_BYTE.toByte())
            } else {
                result.add(b)
            }
        }

        // Checksum (also escape if needed)
        if (checksum == ESCAPE_BYTE) {
            result.add(ESCAPE_BYTE.toByte())
            result.add(ESCAPE_BYTE.toByte())
        } else {
            result.add(checksum.toByte())
        }

        result.add(TRAILER_BYTE.toByte())
        result.add(TRAILER_BYTE.toByte())

        return result.toByteArray()
    }

    // ==================== Command Building ====================

    override fun buildCommand(command: WheelCommand, state: DecoderState?): List<WheelCommand> {
        return when (command) {
            is WheelCommand.SetLight -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 0, if (command.enabled) 1 else 0)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_HEADLIGHT, payload)))
            }

            is WheelCommand.SetHandleButton -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 0, if (command.enabled) 1 else 0)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_HANDLE_BUTTON, payload)))
            }

            is WheelCommand.SetPedalTilt -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 4, command.angle * 65536)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_PEDAL_SPEED_RIDE, payload)))
            }

            is WheelCommand.SetPedalSensitivity -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 4, ((command.sensitivity * 20.48) + 2048).toInt())
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_PEDAL_SPEED_RIDE, payload)))
            }

            is WheelCommand.SetRideMode -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 4, if (command.enabled) 1 else 0)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_PEDAL_SPEED_RIDE, payload)))
            }

            is WheelCommand.SetMaxSpeed -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 4, command.speed * 1000)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_PEDAL_SPEED_RIDE, payload)))
            }

            is WheelCommand.SetSpeakerVolume -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                putIntLE(payload, 0, command.volume * 100)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_SPEAKER_VOLUME, payload)))
            }

            is WheelCommand.SetTransportMode -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                payload[0] = MULTI_CMD_BYTE.toByte()
                putIntLE(payload, 4, if (command.enabled) MULTI_TRANSPORT_ON else MULTI_TRANSPORT_OFF)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_MULTI, payload)))
            }

            is WheelCommand.SetLed -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                payload[0] = MULTI_CMD_BYTE.toByte()
                putIntLE(payload, 4, if (command.enabled) MULTI_LED_ON else MULTI_LED_OFF)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_MULTI, payload)))
            }

            is WheelCommand.SetLock -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                payload[0] = MULTI_CMD_BYTE.toByte()
                putIntLE(payload, 4, if (command.locked) MULTI_LOCK_ON else MULTI_LOCK_OFF)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_MULTI, payload)))
            }

            is WheelCommand.PowerOff -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                payload[0] = MULTI_CMD_BYTE.toByte()
                putIntLE(payload, 4, MULTI_POWER_OFF)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_MULTI, payload)))
            }

            is WheelCommand.Beep -> {
                val payload = ByteArray(CMD_PAYLOAD_SIZE)
                payload[0] = MULTI_CMD_BYTE.toByte()
                putIntLE(payload, 4, MULTI_HORN)
                listOf(WheelCommand.SendBytes(buildCanFrame(CAN_WRITE_MULTI, payload)))
            }

            else -> emptyList()
        }
    }

    // ==================== Init & Lifecycle ====================

    override fun getInitCommands(): List<WheelCommand> {
        val password = DEFAULT_PASSWORD
        val payload = ByteArray(CMD_PAYLOAD_SIZE)
        // Write password bytes into payload
        val pwBytes = password.encodeToByteArray()
        pwBytes.copyInto(payload, 0, 0, minOf(pwBytes.size, CMD_PAYLOAD_SIZE))
        return listOf(WheelCommand.SendBytes(buildCanFrame(CAN_INIT_PASSWORD, payload)))
    }

    override fun isReady(): Boolean = phase == InitPhase.POLLING

    override fun getCapabilities(): CapabilitySet {
        if (phase != InitPhase.POLLING) return CapabilitySet()
        return CapabilitySet(
            supportedCommands = SUPPORTED_COMMANDS,
            detectedModel = "Leaperkim CAN",
            isResolved = true
        )
    }

    override fun getUnpackerStats(): UnpackerStats = unpacker.stats

    override fun reset() {
        unpacker.reset()
        phase = InitPhase.PASSWORD
        modelId = -1
        alternateKeepAlive = false
    }

    override fun getKeepAliveCommand(): WheelCommand? {
        if (phase != InitPhase.POLLING) return null
        alternateKeepAlive = !alternateKeepAlive
        val canId = if (alternateKeepAlive) CAN_READ_VALUES else CAN_READ_STATUS
        return WheelCommand.SendBytes(buildCanFrame(canId, ByteArray(CMD_PAYLOAD_SIZE)))
    }

    override val keepAliveIntervalMs: Long get() = KEEP_ALIVE_INTERVAL_MS

    // ==================== Utility ====================

    /**
     * Write a 32-bit integer in little-endian order into a byte array at the given offset.
     */
    private fun putIntLE(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
