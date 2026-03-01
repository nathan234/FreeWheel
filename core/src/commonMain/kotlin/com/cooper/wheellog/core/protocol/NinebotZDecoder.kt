package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.SmartBms
import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.utils.ByteUtils
import com.cooper.wheellog.core.utils.Lock
import com.cooper.wheellog.core.utils.withLock

/**
 * Frame unpacker for Ninebot Z-series wheels (e.g., Z10).
 *
 * Frame format:
 * - Bytes 0-1: Header (5A A5) - different from regular Ninebot
 * - Byte 2: Length (data length after header, before CRC)
 * - Bytes 3+: Data payload (XOR encrypted with gamma key)
 * - Last 2 bytes: CRC16 checksum
 *
 * The payload is XOR encrypted using a 16-byte gamma key obtained
 * during the connection handshake.
 */
class NinebotZUnpacker : Unpacker {

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

    override fun reset() {
        oldC = 0
        len = 0
        state = State.UNKNOWN
        buffer = ByteArrayBuilder()
    }

    override fun getBuffer(): ByteArray = buffer.toByteArray()

    /**
     * Add a byte to the unpacker.
     * @return true if a complete frame is ready for verification
     */
    override fun addChar(c: Int): Boolean {
        val byte = c and 0xFF

        when (state) {
            State.COLLECTING -> {
                buffer.write(byte)
                // Frame complete when buffer size = length + 9
                // (2 header + 1 len + 5 fixed fields + 2 CRC = 9 + data length)
                if (buffer.size() == len + 9) {
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
                // Looking for header (5A A5)
                if (byte == 0xA5 && oldC == 0x5A) {
                    buffer = ByteArrayBuilder()
                    buffer.write(0x5A)
                    buffer.write(0xA5)
                    state = State.STARTED
                }
                oldC = byte
            }
        }

        return false
    }
}

/**
 * CAN message structure for Ninebot Z protocol.
 * Contains parsed message fields and provides static factory methods
 * for creating various request messages.
 */
class CANMessage private constructor(
    val len: Int,
    val source: Int,
    val destination: Int,
    val command: Int,
    val parameter: Int,
    val data: ByteArray,
    val crc: Int
) {

    /**
     * Address constants for CAN message routing.
     */
    object Addr {
        const val BMS1 = 0x11
        const val BMS2 = 0x12
        const val CONTROLLER = 0x14
        const val KEY_GENERATOR = 0x16
        const val APP = 0x3E
    }

    /**
     * Command type constants.
     */
    object Comm {
        const val READ = 0x01
        const val WRITE = 0x03
        const val GET = 0x04
        const val GET_KEY = 0x5B
    }

    /**
     * Parameter type constants.
     */
    object Param {
        const val GET_KEY = 0x00
        const val SERIAL_NUMBER = 0x10
        const val FIRMWARE = 0x1A
        const val BATTERY_LEVEL = 0x22
        const val ANGLES = 0x61
        const val BAT1_FW = 0x66
        const val BAT2_FW = 0x67
        const val BLE_VERSION = 0x68
        const val ACTIVATION_DATE = 0x69
        const val LOCK_MODE = 0x70
        const val LIMITED_MODE = 0x72
        const val LIMIT_MODE_SPEED_1KM = 0x73
        const val LIMIT_MODE_SPEED = 0x74
        const val CALIBRATION = 0x75
        const val ALARMS = 0x7C
        const val ALARM1_SPEED = 0x7D
        const val ALARM2_SPEED = 0x7E
        const val ALARM3_SPEED = 0x7F
        const val LIVE_DATA = 0xB0
        const val LED_MODE = 0xC6
        const val LED_COLOR1 = 0xC8
        const val LED_COLOR2 = 0xCA
        const val LED_COLOR3 = 0xCC
        const val LED_COLOR4 = 0xCE
        const val PEDAL_SENSITIVITY = 0xD2
        const val DRIVE_FLAGS = 0xD3
        const val SPEAKER_VOLUME = 0xF5

        // BMS parameters
        const val BMS_SN = 0x10
        const val BMS_LIFE = 0x30
        const val BMS_CELLS = 0x40
    }

    companion object {

        /**
         * Parse and verify a received CAN message buffer.
         * Decrypts the payload and verifies the CRC checksum.
         *
         * @param buffer Raw buffer including 5A A5 header
         * @param gamma 16-byte XOR encryption key
         * @return Parsed CANMessage if CRC is valid, null otherwise
         */
        fun verify(buffer: ByteArray, gamma: ByteArray): CANMessage? {
            if (buffer.size < 9) return null

            // Extract data after header (skip 5A A5)
            val dataBuffer = buffer.copyOfRange(2, buffer.size)

            // Decrypt the data
            val decrypted = crypto(dataBuffer, gamma)

            // Verify CRC
            val providedCrc = ((decrypted[decrypted.size - 1].toInt() and 0xFF) shl 8) or
                    (decrypted[decrypted.size - 2].toInt() and 0xFF)
            val dataForCrc = decrypted.copyOfRange(0, decrypted.size - 2)
            val calculatedCrc = computeCheck(dataForCrc)

            return if (providedCrc == calculatedCrc) {
                parse(decrypted)
            } else {
                null
            }
        }

        /**
         * Parse decrypted data buffer into a CANMessage.
         */
        private fun parse(data: ByteArray): CANMessage? {
            if (data.size < 7) return null

            val len = data[0].toInt() and 0xFF
            val source = data[1].toInt() and 0xFF
            val destination = data[2].toInt() and 0xFF
            val command = data[3].toInt() and 0xFF
            val parameter = data[4].toInt() and 0xFF
            val payload = if (data.size > 7) {
                data.copyOfRange(5, data.size - 2)
            } else {
                ByteArray(0)
            }
            val crc = ((data[data.size - 1].toInt() and 0xFF) shl 8) or
                    (data[data.size - 2].toInt() and 0xFF)

            return CANMessage(len, source, destination, command, parameter, payload, crc)
        }

        /**
         * XOR encrypt/decrypt data using gamma key.
         * First byte is not encrypted.
         */
        fun crypto(buffer: ByteArray, gamma: ByteArray): ByteArray {
            val result = buffer.copyOf()
            for (j in 1 until result.size) {
                result[j] = (result[j].toInt() xor gamma[(j - 1) % 16].toInt()).toByte()
            }
            return result
        }

        /**
         * Compute CRC16 checksum for Ninebot Z protocol.
         */
        fun computeCheck(buffer: ByteArray): Int {
            var check = 0
            for (c in buffer) {
                check += (c.toInt() and 0xFF)
            }
            check = check xor 0xFFFF
            check = check and 0xFFFF
            return check
        }

        /**
         * Build a complete message buffer including header and CRC.
         */
        fun buildMessage(
            source: Int,
            destination: Int,
            command: Int,
            parameter: Int,
            data: ByteArray,
            gamma: ByteArray
        ): ByteArray {
            val len = data.size

            // Build raw message: len, source, dest, cmd, param, data, crc
            val rawMessage = ByteArray(len + 7)
            rawMessage[0] = len.toByte()
            rawMessage[1] = source.toByte()
            rawMessage[2] = destination.toByte()
            rawMessage[3] = command.toByte()
            rawMessage[4] = parameter.toByte()
            data.copyInto(rawMessage, 5)

            // Calculate CRC (on message without CRC)
            val crc = computeCheck(rawMessage.copyOfRange(0, len + 5))
            rawMessage[len + 5] = (crc and 0xFF).toByte()
            rawMessage[len + 6] = ((crc shr 8) and 0xFF).toByte()

            // Encrypt
            val encrypted = crypto(rawMessage, gamma)

            // Add header
            val result = ByteArray(encrypted.size + 2)
            result[0] = 0x5A.toByte()
            result[1] = 0xA5.toByte()
            encrypted.copyInto(result, 2)

            return result
        }

        // Factory methods for creating request messages

        fun getBleVersion(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.BLE_VERSION, byteArrayOf(0x02), gamma
        )

        fun getKey(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.KEY_GENERATOR, Comm.GET_KEY, Param.GET_KEY, ByteArray(0), gamma
        )

        fun getSerialNumber(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.SERIAL_NUMBER, byteArrayOf(0x0E), gamma
        )

        fun getVersion(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.FIRMWARE, byteArrayOf(0x06), gamma
        )

        fun getLiveData(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.LIVE_DATA, byteArrayOf(0x20), gamma
        )

        fun getParams1(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.LOCK_MODE, byteArrayOf(0x20), gamma
        )

        fun getParams2(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.LED_MODE, byteArrayOf(0x1C), gamma
        )

        fun getParams3(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.CONTROLLER, Comm.READ, Param.SPEAKER_VOLUME, byteArrayOf(0x02), gamma
        )

        fun getBms1Sn(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS1, Comm.READ, Param.BMS_SN, byteArrayOf(0x22), gamma
        )

        fun getBms1Life(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS1, Comm.READ, Param.BMS_LIFE, byteArrayOf(0x18), gamma
        )

        fun getBms1Cells(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS1, Comm.READ, Param.BMS_CELLS, byteArrayOf(0x20), gamma
        )

        fun getBms2Sn(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS2, Comm.READ, Param.BMS_SN, byteArrayOf(0x22), gamma
        )

        fun getBms2Life(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS2, Comm.READ, Param.BMS_LIFE, byteArrayOf(0x18), gamma
        )

        fun getBms2Cells(gamma: ByteArray): ByteArray = buildMessage(
            Addr.APP, Addr.BMS2, Comm.READ, Param.BMS_CELLS, byteArrayOf(0x20), gamma
        )
    }
}

/**
 * Connection state machine for Ninebot Z protocol.
 * The wheel requires a specific handshake sequence before sending telemetry.
 */
enum class NinebotZConnectionState(val value: Int) {
    INIT(0),
    WAIT_KEY(1),
    SERIAL_NUMBER(2),
    VERSION(3),
    PARAMS1(4),
    PARAMS2(5),
    PARAMS3(6),
    BMS1_SN(7),
    BMS1_LIFE(8),
    BMS1_CELLS(9),
    BMS2_SN(10),
    BMS2_LIFE(11),
    BMS2_CELLS(12),
    READY(13);

    companion object {
        fun fromValue(value: Int): NinebotZConnectionState =
            entries.find { it.value == value } ?: INIT
    }
}

/**
 * Ninebot Z-series protocol decoder.
 *
 * Supports:
 * - Ninebot Z10
 * - Other Z-series Ninebot wheels
 *
 * Features:
 * - Gamma XOR encryption with 16-byte key
 * - CRC16 checksum verification
 * - Dual BMS support with cell voltage, temperature, and health data
 * - Connection state machine for proper handshake sequence
 *
 * Uses CAN messages over BLE with gamma XOR encryption.
 * Connection requires a 14-step handshake before live data flows.
 *
 * State machine:
 *   INIT → WAIT_KEY → SERIAL_NUMBER → VERSION → PARAMS1 → PARAMS2 → PARAMS3
 *     → BMS1_SN → BMS1_LIFE → BMS1_CELLS → BMS2_SN → BMS2_LIFE → BMS2_CELLS → READY
 *                                            └── skipped when bmsReadingMode=false ──┘
 *
 * Each state sends the corresponding request via getKeepAliveCommand().
 * State advances when the matching response is received in decode().
 *
 * This class is thread-safe.
 */
class NinebotZDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.NINEBOT_Z
    private val stateLock = Lock()

    private val unpacker = NinebotZUnpacker()
    private var gamma = ByteArray(16) { 0 }
    private var connectionState = NinebotZConnectionState.INIT
    private var bmsReadingMode = false

    private var bms1 = SmartBms()
    private var bms2 = SmartBms()

    // Wheel settings (parsed from params messages)
    private var lockMode = 0
    private var limitedMode = 0
    private var limitModeSpeed = 0
    private var alarms = 0
    private var alarm1Speed = 0
    private var alarm2Speed = 0
    private var alarm3Speed = 0
    private var ledMode = 0
    private var ledColor1 = 0
    private var ledColor2 = 0
    private var ledColor3 = 0
    private var ledColor4 = 0
    private var pedalSensitivity = 0
    private var driveFlags = 0
    private var speakerVolume = 0

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
        return stateLock.withLock {
            decodeFrames(data, unpacker, currentState) { buffer, state ->
                val msg = CANMessage.verify(buffer, gamma) ?: return@decodeFrames null
                processMessage(msg, state)
            }?.let { result ->
                result.copy(newState = result.newState.copy(
                    bms1 = bms1.toSnapshot(),
                    bms2 = bms2.toSnapshot()
                ))
            }
        }
    }

    /**
     * Process a verified CAN message and update state accordingly.
     */
    private fun processMessage(
        msg: CANMessage,
        currentState: WheelState
    ): FrameResult? {

        when {
            // BLE Version response - initial connection confirmation
            msg.parameter == CANMessage.Param.BLE_VERSION &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                connectionState = NinebotZConnectionState.SERIAL_NUMBER
                return FrameResult(currentState)
            }

            // Encryption key received
            msg.parameter == CANMessage.Param.GET_KEY &&
                    msg.source == CANMessage.Addr.KEY_GENERATOR -> {
                gamma = parseKey(msg.data)
                connectionState = NinebotZConnectionState.SERIAL_NUMBER
                return FrameResult(currentState)
            }

            // Serial number received
            msg.parameter == CANMessage.Param.SERIAL_NUMBER &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                val serial = parseSerialNumber(msg.data)
                connectionState = NinebotZConnectionState.VERSION
                return FrameResult(
                    currentState.copy(serialNumber = serial, model = "Ninebot Z")
                )
            }

            // Firmware version received
            msg.parameter == CANMessage.Param.FIRMWARE &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                val result = parseVersionNumber(msg.data)
                connectionState = NinebotZConnectionState.PARAMS1
                return FrameResult(
                    currentState.copy(version = result.first, error = result.second)
                )
            }

            // Params1 (lock mode, alarms, etc.)
            msg.parameter == CANMessage.Param.LOCK_MODE &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                parseParams1(msg.data)
                connectionState = NinebotZConnectionState.PARAMS2
                return FrameResult(currentState)
            }

            // Params2 (LED mode, pedal sensitivity, etc.)
            msg.parameter == CANMessage.Param.LED_MODE &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                parseParams2(msg.data)
                connectionState = NinebotZConnectionState.PARAMS3
                return FrameResult(currentState)
            }

            // Params3 (speaker volume)
            msg.parameter == CANMessage.Param.SPEAKER_VOLUME &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                parseParams3(msg.data)
                connectionState = NinebotZConnectionState.READY
                return FrameResult(currentState)
            }

            // Live telemetry data
            msg.parameter == CANMessage.Param.LIVE_DATA &&
                    msg.source == CANMessage.Addr.CONTROLLER -> {
                val newState = parseLiveData(msg.data, currentState)
                return FrameResult(newState, hasNewData = true)
            }

            // BMS1 responses
            msg.source == CANMessage.Addr.BMS1 -> {
                when (msg.parameter) {
                    CANMessage.Param.BMS_SN -> {
                        parseBmsSn(msg.data, 1)
                        connectionState = NinebotZConnectionState.BMS1_LIFE
                    }
                    CANMessage.Param.BMS_LIFE -> {
                        parseBmsLife(msg.data, 1)
                        connectionState = NinebotZConnectionState.BMS1_CELLS
                    }
                    CANMessage.Param.BMS_CELLS -> {
                        parseBmsCells(msg.data, 1)
                        connectionState = NinebotZConnectionState.BMS2_SN
                    }
                }
                return FrameResult(currentState)
            }

            // BMS2 responses
            msg.source == CANMessage.Addr.BMS2 -> {
                when (msg.parameter) {
                    CANMessage.Param.BMS_SN -> {
                        parseBmsSn(msg.data, 2)
                        connectionState = NinebotZConnectionState.BMS2_LIFE
                    }
                    CANMessage.Param.BMS_LIFE -> {
                        parseBmsLife(msg.data, 2)
                        connectionState = NinebotZConnectionState.BMS2_CELLS
                    }
                    CANMessage.Param.BMS_CELLS -> {
                        parseBmsCells(msg.data, 2)
                        connectionState = NinebotZConnectionState.READY
                    }
                }
                return FrameResult(currentState)
            }
        }

        return FrameResult(currentState)
    }

    // ========== Parsing Methods ==========

    private fun parseKey(data: ByteArray): ByteArray {
        return data.copyOfRange(0, minOf(data.size, 16))
    }

    private fun parseSerialNumber(data: ByteArray): String {
        return data.decodeToString().trim('\u0000')
    }

    private fun parseVersionNumber(data: ByteArray): Pair<String, String> {
        if (data.size < 6) return Pair("", "")

        val version = buildString {
            append(((data[1].toInt() and 0x0F).toString(16)))
            append(".")
            append((((data[0].toInt() shr 4) and 0x0F).toString(16)))
            append(".")
            append(((data[0].toInt() and 0x0F).toString(16)))
        }

        val error1 = data[2].toInt() and 0xFF
        val error2 = data[3].toInt() and 0xFF

        val errorStr = when {
            error1 != 0 -> {
                val e1 = getErrorString(error1)
                if (error2 != 0) "$e1\n${getErrorString(error2)}" else e1
            }
            else -> "No"
        }

        return Pair(version, errorStr)
    }

    private fun getErrorString(errorCode: Int): String {
        val text = when (errorCode) {
            0 -> ""
            1 -> "Motor hall sensor error"
            6 -> "Initial S/N"
            8 -> "Error Bat input 1"
            9 -> "Error Bat input 2"
            10 -> "Abnormal communication Bat#1"
            11 -> "Abnormal communication Bat#2"
            12 -> "Failure of Gyroscope initialization"
            24 -> "General voltage > 65V or < 40V"
            25 -> "VGM - Voltage < 10V"
            28 -> "Abnormal power supply Bat#1"
            29 -> "Abnormal power supply Bat#2"
            34 -> "Battery cell of Bat#1 in big differential voltage"
            35 -> "Battery cell of Bat#2 in big differential voltage"
            36 -> "Bat#1 input error 0x800"
            37 -> "Bat#2 input error 0x800"
            38 -> "3c1e8 != 0x5A"
            46 -> "Unknown error"
            else -> "Error"
        }
        return "Err:$errorCode $text"
    }

    private fun parseParams1(data: ByteArray) {
        if (data.size < 32) return

        lockMode = ByteUtils.shortFromBytesLE(data, 0)
        limitedMode = ByteUtils.shortFromBytesLE(data, 4)
        // limitModeSpeed1Km = ByteUtils.shortFromBytesLE(data, 6) / 100
        limitModeSpeed = ByteUtils.shortFromBytesLE(data, 8) / 100
        alarms = ByteUtils.shortFromBytesLE(data, 24)
        alarm1Speed = ByteUtils.shortFromBytesLE(data, 26) / 100
        alarm2Speed = ByteUtils.shortFromBytesLE(data, 28) / 100
        alarm3Speed = ByteUtils.shortFromBytesLE(data, 30) / 100
    }

    private fun parseParams2(data: ByteArray) {
        if (data.size < 28) return

        ledMode = ByteUtils.shortFromBytesLE(data, 0)
        ledColor1 = (ByteUtils.intFromBytesLE(data, 4) shr 16) and 0xFF
        ledColor2 = (ByteUtils.intFromBytesLE(data, 8) shr 16) and 0xFF
        ledColor3 = (ByteUtils.intFromBytesLE(data, 12) shr 16) and 0xFF
        ledColor4 = (ByteUtils.intFromBytesLE(data, 16) shr 16) and 0xFF
        pedalSensitivity = ByteUtils.shortFromBytesLE(data, 24)
        driveFlags = ByteUtils.shortFromBytesLE(data, 26)
    }

    private fun parseParams3(data: ByteArray) {
        if (data.size < 2) return
        speakerVolume = ByteUtils.shortFromBytesLE(data, 0) shr 3
    }

    private fun parseLiveData(data: ByteArray, currentState: WheelState): WheelState {
        if (data.size < 28) return currentState

        // val errorCode = ByteUtils.shortFromBytesLE(data, 0)
        // val alarmCode = ByteUtils.shortFromBytesLE(data, 2)
        // val escStatus = ByteUtils.shortFromBytesLE(data, 4)
        val battery = ByteUtils.shortFromBytesLE(data, 8)
        val speed = ByteUtils.shortFromBytesLE(data, 10)
        // val avgSpeed = ByteUtils.shortFromBytesLE(data, 12)
        val distance = ByteUtils.intFromBytesLE(data, 14).toLong()
        val tripDistance = ByteUtils.shortFromBytesLE(data, 18) * 10L
        // val operatingTime = ByteUtils.shortFromBytesLE(data, 20)
        val temperature = ByteUtils.signedShortFromBytesLE(data, 22)
        val voltage = ByteUtils.shortFromBytesLE(data, 24)
        val current = ByteUtils.signedShortFromBytesLE(data, 26)

        // Calculate power
        val power = ((current / 100.0) * voltage).toInt()

        return currentState.copy(
            speed = speed,
            voltage = voltage,
            current = current,
            power = power,
            temperature = temperature * 10, // Convert to 1/100 C format
            totalDistance = distance,
            wheelDistance = tripDistance,
            batteryLevel = battery,
            wheelType = WheelType.NINEBOT_Z,
            model = "Ninebot Z"
        )
    }

    // ========== BMS Parsing Methods ==========

    private fun parseBmsSn(data: ByteArray, bmsNum: Int) {
        if (data.size < 34) return

        val bms = if (bmsNum == 1) bms1 else bms2

        val serialNumber = data.copyOfRange(0, 14).decodeToString().trim('\u0000')

        val versionNumber = buildString {
            append((data[15].toInt() and 0xFF).toString(16).uppercase())
            append(".")
            append((((data[14].toInt() shr 4) and 0x0F).toString(16).uppercase()))
            append(".")
            append(((data[14].toInt() and 0x0F).toString(16).uppercase()))
        }

        val factoryCap = ByteUtils.shortFromBytesLE(data, 16)
        val actualCap = ByteUtils.shortFromBytesLE(data, 18)
        val fullCycles = ByteUtils.shortFromBytesLE(data, 22)
        val chargeCount = ByteUtils.shortFromBytesLE(data, 24)
        val mfgDate = ByteUtils.shortFromBytesLE(data, 32)

        val year = mfgDate shr 9
        val month = (mfgDate shr 5) and 0x0F
        val day = mfgDate and 0x1F
        val mfgDateStr = "${day.toString().padStart(2, '0')}.${month.toString().padStart(2, '0')}.20${year.toString().padStart(2, '0')}"

        bms.serialNumber = serialNumber
        bms.versionNumber = versionNumber
        bms.factoryCap = factoryCap
        bms.actualCap = actualCap
        bms.fullCycles = fullCycles
        bms.chargeCount = chargeCount
        bms.mfgDateStr = mfgDateStr
    }

    private fun parseBmsLife(data: ByteArray, bmsNum: Int) {
        if (data.size < 24) return

        val bms = if (bmsNum == 1) bms1 else bms2

        val bmsStatus = ByteUtils.shortFromBytesLE(data, 0)
        val remCap = ByteUtils.shortFromBytesLE(data, 2)
        val remPerc = ByteUtils.shortFromBytesLE(data, 4)
        val current = ByteUtils.signedShortFromBytesLE(data, 6)
        val voltage = ByteUtils.shortFromBytesLE(data, 8)
        val temp1 = (data[10].toInt() and 0xFF) - 20
        val temp2 = (data[11].toInt() and 0xFF) - 20
        val balanceMap = ByteUtils.shortFromBytesLE(data, 12)
        val health = ByteUtils.shortFromBytesLE(data, 22)

        bms.status = bmsStatus
        bms.remCap = remCap
        bms.remPerc = remPerc
        bms.current = current / 100.0
        bms.voltage = voltage / 100.0
        bms.temp1 = temp1.toDouble()
        bms.temp2 = temp2.toDouble()
        bms.balanceMap = balanceMap
        bms.health = health
    }

    private fun parseBmsCells(data: ByteArray, bmsNum: Int) {
        if (data.size < 32) return

        val bms = if (bmsNum == 1) bms1 else bms2

        // Read up to 16 cells
        for (i in 0 until 16) {
            val offset = i * 2
            if (offset + 1 < data.size) {
                val cellVoltage = ByteUtils.shortFromBytesLE(data, offset)
                bms.cells[i] = cellVoltage / 1000.0
            }
        }

        // Determine actual cell count (cells with voltage > 0)
        var cellNum = 14 // Default for Ninebot Z
        if (bms.cells[14] > 0) cellNum = 15
        if (bms.cells[15] > 0) cellNum = 16
        bms.cellNum = cellNum

        // Calculate min/max/avg cell voltages
        updateBmsCellStats(bms)
    }

    private fun updateBmsCellStats(bms: SmartBms) {
        if (bms.cellNum == 0) return

        bms.minCell = bms.cells[0]
        bms.maxCell = bms.cells[0]
        bms.minCellNum = 1
        bms.maxCellNum = 1
        var totalVolt = 0.0

        for (i in 0 until bms.cellNum) {
            val cell = bms.cells[i]
            if (cell > 0.0) {
                totalVolt += cell
                if (bms.maxCell < cell) {
                    bms.maxCell = cell
                    bms.maxCellNum = i + 1
                }
                if (bms.minCell > cell) {
                    bms.minCell = cell
                    bms.minCellNum = i + 1
                }
            }
        }

        bms.cellDiff = bms.maxCell - bms.minCell
        bms.avgCell = if (bms.cellNum > 0) totalVolt / bms.cellNum else 0.0
    }

    // ========== Keep-alive and Initialization ==========

    override val keepAliveIntervalMs: Long = 125L

    override fun getKeepAliveCommand(): WheelCommand? = stateLock.withLock {
        // Return the appropriate command based on connection state
        when (connectionState) {
            NinebotZConnectionState.INIT ->
                WheelCommand.SendBytes(CANMessage.getBleVersion(gamma))
            NinebotZConnectionState.WAIT_KEY ->
                WheelCommand.SendBytes(CANMessage.getKey(gamma))
            NinebotZConnectionState.SERIAL_NUMBER ->
                WheelCommand.SendBytes(CANMessage.getSerialNumber(gamma))
            NinebotZConnectionState.VERSION ->
                WheelCommand.SendBytes(CANMessage.getVersion(gamma))
            NinebotZConnectionState.PARAMS1 ->
                WheelCommand.SendBytes(CANMessage.getParams1(gamma))
            NinebotZConnectionState.PARAMS2 ->
                WheelCommand.SendBytes(CANMessage.getParams2(gamma))
            NinebotZConnectionState.PARAMS3 ->
                WheelCommand.SendBytes(CANMessage.getParams3(gamma))
            NinebotZConnectionState.BMS1_SN ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms1Sn(gamma)) else null
            NinebotZConnectionState.BMS1_LIFE ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms1Life(gamma)) else null
            NinebotZConnectionState.BMS1_CELLS ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms1Cells(gamma)) else null
            NinebotZConnectionState.BMS2_SN ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms2Sn(gamma)) else null
            NinebotZConnectionState.BMS2_LIFE ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms2Life(gamma)) else null
            NinebotZConnectionState.BMS2_CELLS ->
                if (bmsReadingMode) WheelCommand.SendBytes(CANMessage.getBms2Cells(gamma)) else null
            NinebotZConnectionState.READY ->
                WheelCommand.SendBytes(CANMessage.getLiveData(gamma))
        }
    }

    override fun getInitCommands(): List<WheelCommand> {
        return listOf(WheelCommand.SendBytes(CANMessage.getBleVersion(gamma)))
    }

    override fun isReady(): Boolean {
        return stateLock.withLock {
            connectionState == NinebotZConnectionState.READY
        }
    }

    override fun reset() {
        stateLock.withLock {
            unpacker.reset()
            gamma = ByteArray(16) { 0 }
            connectionState = NinebotZConnectionState.INIT
            bmsReadingMode = false
            bms1 = SmartBms()
            bms2 = SmartBms()
            lockMode = 0
            limitedMode = 0
            limitModeSpeed = 0
            alarms = 0
            alarm1Speed = 0
            alarm2Speed = 0
            alarm3Speed = 0
            ledMode = 0
            ledColor1 = 0
            ledColor2 = 0
            ledColor3 = 0
            ledColor4 = 0
            pedalSensitivity = 0
            driveFlags = 0
            speakerVolume = 0
        }
    }

    override fun buildCommand(command: WheelCommand): List<WheelCommand> {
        return stateLock.withLock {
            val g = gamma
            when (command) {
                is WheelCommand.SetDrl -> {
                    // Bit 0: DRL
                    val newFlags = (driveFlags and 0xFFFE) or (if (command.enabled) 1 else 0)
                    driveFlags = newFlags
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.DRIVE_FLAGS, shortToLE(newFlags), g)))
                }
                is WheelCommand.SetLight -> {
                    // Bit 2: Light
                    val newFlags = (driveFlags and 0xFFFB) or ((if (command.enabled) 1 else 0) shl 2)
                    driveFlags = newFlags
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.DRIVE_FLAGS, shortToLE(newFlags), g)))
                }
                is WheelCommand.SetTailLight -> {
                    // Bit 1: Tail light
                    val newFlags = (driveFlags and 0xFFFD) or ((if (command.enabled) 1 else 0) shl 1)
                    driveFlags = newFlags
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.DRIVE_FLAGS, shortToLE(newFlags), g)))
                }
                is WheelCommand.SetHandleButton -> {
                    // Bit 3: Handle button (inverted: enabled=true clears bit)
                    val newFlags = (driveFlags and 0xFFF7) or ((if (command.enabled) 0 else 1) shl 3)
                    driveFlags = newFlags
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.DRIVE_FLAGS, shortToLE(newFlags), g)))
                }
                is WheelCommand.SetBrakeAssist -> {
                    // Bit 4: Brake assist (inverted: enabled=true clears bit)
                    val newFlags = (driveFlags and 0xFFEF) or ((if (command.enabled) 0 else 1) shl 4)
                    driveFlags = newFlags
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.DRIVE_FLAGS, shortToLE(newFlags), g)))
                }
                is WheelCommand.SetLedColor -> {
                    val param = CANMessage.Param.LED_COLOR1 + (command.ledNum - 1) * 2
                    val data = if (command.value < 256) {
                        byteArrayOf(0xF0.toByte(), (command.value and 0xFF).toByte(), 0x00, 0x00)
                    } else {
                        byteArrayOf(0x00, 0x00, 0x00, 0x00)
                    }
                    listOf(WheelCommand.SendBytes(CANMessage.buildMessage(CANMessage.Addr.APP, CANMessage.Addr.CONTROLLER, CANMessage.Comm.WRITE, param, data, g)))
                }
                is WheelCommand.SetAlarmEnabled -> {
                    val bit = command.num - 1
                    val mask = 1 shl bit
                    alarms = (alarms and mask.inv()) or ((if (command.enabled) 1 else 0) shl bit)
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.ALARMS, shortToLE(alarms), g)))
                }
                is WheelCommand.SetAlarmSpeed -> {
                    val param = when (command.num) {
                        1 -> CANMessage.Param.ALARM1_SPEED
                        2 -> CANMessage.Param.ALARM2_SPEED
                        3 -> CANMessage.Param.ALARM3_SPEED
                        else -> return@withLock emptyList()
                    }
                    val speed = command.speed * 100
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, param, shortToLE(speed), g)))
                }
                is WheelCommand.SetLimitedMode -> {
                    val value: Byte = if (command.enabled) 1 else 0
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.LIMITED_MODE, byteArrayOf(value), g)))
                }
                is WheelCommand.SetLimitedSpeed -> {
                    val speed = command.speed * 100
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.LIMIT_MODE_SPEED, shortToLE(speed), g)))
                }
                is WheelCommand.SetPedalSensitivity -> {
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.PEDAL_SENSITIVITY, shortToLE(command.sensitivity), g)))
                }
                is WheelCommand.SetLedMode -> {
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.LED_MODE, shortToLE(command.mode), g)))
                }
                is WheelCommand.SetSpeakerVolume -> {
                    val value = command.volume shl 3
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.SPEAKER_VOLUME, shortToLE(value), g)))
                }
                is WheelCommand.SetLock -> {
                    val value: Byte = if (command.locked) 1 else 0
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.LOCK_MODE, byteArrayOf(value, 0x00), g)))
                }
                is WheelCommand.Calibrate -> {
                    listOf(WheelCommand.SendBytes(writeMessage(CANMessage.Comm.WRITE, CANMessage.Param.CALIBRATION, byteArrayOf(0x01, 0x00), g)))
                }
                else -> emptyList()
            }
        }
    }

    private fun writeMessage(command: Int, parameter: Int, data: ByteArray, gamma: ByteArray): ByteArray {
        return CANMessage.buildMessage(CANMessage.Addr.APP, CANMessage.Addr.CONTROLLER, command, parameter, data, gamma)
    }

    private fun shortToLE(value: Int): ByteArray {
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    /**
     * Enable or disable BMS reading mode.
     * When enabled, the decoder will request BMS data during keep-alive cycles.
     */
    fun setBmsReadingMode(enabled: Boolean) = stateLock.withLock {
        bmsReadingMode = enabled
        if (enabled && connectionState == NinebotZConnectionState.READY) {
            connectionState = NinebotZConnectionState.BMS1_SN
        }
    }

    /**
     * Get the number of cells for this wheel type.
     */
    fun getCellsForWheel(): Int = 14

    /**
     * Get the current gamma encryption key.
     */
    fun getGamma(): ByteArray = stateLock.withLock { gamma.copyOf() }

    /**
     * Set the gamma encryption key (used when restoring state).
     */
    fun setGamma(newGamma: ByteArray) = stateLock.withLock {
        if (newGamma.size == 16) {
            gamma = newGamma.copyOf()
        }
    }
}
