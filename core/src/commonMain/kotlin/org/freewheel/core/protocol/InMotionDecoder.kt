package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * InMotion V1 protocol decoder.
 *
 * Supports InMotion wheels using the original CAN-over-BLE protocol:
 * - V5, V5F, V5D, V5+
 * - V8, V8F, V8S
 * - V10, V10F, V10S, V10SF, V10T, V10FT
 * - Glide 3
 * - R-series, L6, Lively
 *
 * Frame format: AA AA [data with 0xA5 escapes] [checksum] 55 55
 *
 * CAN message fields (after AA AA header + escape removal):
 * - Bytes 0-3:  CAN ID (identifies message type)
 *     0x0F550113 = GetFastInfo (live telemetry)
 *     0x0F550114 = GetSlowInfo (model, version, serial, settings)
 *     0x0F780101 = Alert
 * - Bytes 4-11: CAN data (8 bytes)
 * - Byte 12:    Length
 * - Byte 13:    Channel
 * - Byte 14:    Format (0=standard, 1=extended)
 * - Byte 15:    Type
 * - Bytes 16+:  Extended data (when format=1)
 *
 * This class is thread-safe.
 */
class InMotionDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.INMOTION

    private val stateLock = Lock()
    private val unpacker = InMotionUnpacker()
    private var model = Model.UNKNOWN
    private var isReady = false
    private var needSlowData = true

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
        return stateLock.withLock {
            decodeFrames(data, unpacker, currentState) { buffer, state ->
                processFrame(buffer, state, config)
            }
        }
    }

    private fun processFrame(
        buffer: ByteArray,
        currentState: WheelState,
        config: DecoderConfig
    ): FrameResult? {
        val canMessage = CANMessage.verify(buffer) ?: return null
        val idValue = IDValue.fromInt(canMessage.id)

        return when (idValue) {
            IDValue.GetFastInfo -> {
                val result = canMessage.parseFastInfoMessage(model, currentState, config)
                if (result != null) {
                    FrameResult(result.state, hasNewData = true, news = result.news)
                } else {
                    FrameResult(currentState)
                }
            }

            IDValue.Alert -> {
                val alertResult = canMessage.parseAlertInfoMessage(currentState)
                if (alertResult != null) {
                    FrameResult(alertResult.state, news = alertResult.news)
                } else {
                    FrameResult(currentState)
                }
            }

            IDValue.GetSlowInfo -> {
                if (canMessage.isValid()) {
                    needSlowData = false
                }
                val result = canMessage.parseSlowInfoMessage(currentState)
                if (result != null) {
                    if (result.detectedModel != Model.UNKNOWN) {
                        model = result.detectedModel
                        isReady = true
                    }
                    FrameResult(result.state)
                } else {
                    FrameResult(currentState)
                }
            }

            IDValue.PinCode -> {
                FrameResult(currentState)
            }

            IDValue.Calibration -> {
                val news = if (canMessage.data[0] == 1.toByte()) {
                    "Calibration success"
                } else {
                    "Calibration failed"
                }
                FrameResult(currentState, news = news)
            }

            IDValue.RideMode -> {
                val news = if (canMessage.data[0] == 1.toByte()) {
                    "Ride mode changed"
                } else {
                    "Ride mode change failed"
                }
                FrameResult(currentState, news = news)
            }

            IDValue.Light -> {
                val news = if (canMessage.data[0] == 1.toByte()) {
                    "Light toggled"
                } else {
                    "Light toggle failed"
                }
                FrameResult(currentState, news = news)
            }

            IDValue.HandleButton -> {
                val news = if (canMessage.data[0] == 1.toByte()) {
                    "Handle button setting changed"
                } else {
                    "Handle button setting failed"
                }
                FrameResult(currentState, news = news)
            }

            IDValue.SpeakerVolume -> {
                val news = if (canMessage.data[0] == 1.toByte()) {
                    "Speaker volume changed"
                } else {
                    "Speaker volume change failed"
                }
                FrameResult(currentState, news = news)
            }

            else -> {
                FrameResult(currentState)
            }
        }
    }

    override fun isReady(): Boolean = stateLock.withLock { isReady && model != Model.UNKNOWN }

    override fun reset() {
        stateLock.withLock {
            unpacker.reset()
            model = Model.UNKNOWN
            isReady = false
            needSlowData = true
        }
    }

    override fun buildCommand(command: WheelCommand): List<WheelCommand> {
        return when (command) {
            is WheelCommand.Beep -> listOf(
                WheelCommand.SendBytes(CANMessage.wheelBeep().writeBuffer())
            )
            is WheelCommand.SetLight -> listOf(
                WheelCommand.SendBytes(CANMessage.setLight(command.enabled).writeBuffer())
            )
            is WheelCommand.SetLed -> listOf(
                WheelCommand.SendBytes(CANMessage.setLed(command.enabled).writeBuffer())
            )
            is WheelCommand.PowerOff -> listOf(
                WheelCommand.SendBytes(CANMessage.powerOff().writeBuffer())
            )
            is WheelCommand.Calibrate -> listOf(
                WheelCommand.SendBytes(CANMessage.wheelCalibration().writeBuffer())
            )
            is WheelCommand.SetMaxSpeed -> listOf(
                WheelCommand.SendBytes(CANMessage.setMaxSpeed(command.speed).writeBuffer())
            )
            is WheelCommand.SetHandleButton -> listOf(
                WheelCommand.SendBytes(CANMessage.setHandleButton(command.enabled).writeBuffer())
            )
            is WheelCommand.SetSpeakerVolume -> listOf(
                WheelCommand.SendBytes(CANMessage.setSpeakerVolume(command.volume).writeBuffer())
            )
            is WheelCommand.SetRideMode -> listOf(
                WheelCommand.SendBytes(CANMessage.setRideMode(command.enabled).writeBuffer())
            )
            is WheelCommand.SetPedalSensitivity -> listOf(
                WheelCommand.SendBytes(CANMessage.setPedalSensitivity(command.sensitivity).writeBuffer())
            )
            is WheelCommand.SetPedalTilt -> listOf(
                WheelCommand.SendBytes(CANMessage.setTiltHorizon(command.angle).writeBuffer())
            )
            else -> emptyList()
        }
    }

    override fun getInitCommands(): List<WheelCommand> {
        return emptyList()
    }

    override fun getKeepAliveCommand(): WheelCommand? {
        return WheelCommand.SendBytes(CANMessage.standardMessage().writeBuffer())
    }

    override val keepAliveIntervalMs: Long = 250L

    /**
     * Get the maximum speed for the current wheel model (km/h).
     */
    fun getMaxSpeed(): Int {
        return when (model) {
            Model.V5, Model.V5PLUS, Model.V5F, Model.V5D -> 25
            Model.V8, Model.Glide3 -> 35
            Model.V8F, Model.V8S, Model.V10S, Model.V10SF,
            Model.V10, Model.V10F, Model.V10T, Model.V10FT -> 45
            else -> 70
        }
    }

    /**
     * Check if the wheel has LED support.
     */
    fun hasLedSupport(): Boolean {
        return when (model) {
            Model.Glide3, Model.V8, Model.V8F, Model.V8S,
            Model.V10S, Model.V10SF, Model.V10T,
            Model.V10, Model.V10F, Model.V10FT -> true
            else -> false
        }
    }

    // ==================== InMotion Model Enum ====================

    /**
     * InMotion wheel models with their ID codes and speed calculation factors.
     */
    enum class Model(val id: String, val speedCalculationFactor: Double) {
        R1N("0", 3812.0),
        R1S("1", 1000.0),
        R1CF("2", 3812.0),
        R1AP("3", 3812.0),
        R1EX("4", 3812.0),
        R1Sample("5", 1000.0),
        R1T("6", 3810.0),
        R10("7", 3812.0),
        V3("10", 3812.0),
        V3C("11", 3812.0),
        V3PRO("12", 3812.0),
        V3S("13", 3812.0),
        R2N("21", 3812.0),
        R2S("22", 3812.0),
        R2Sample("23", 3812.0),
        R2("20", 3812.0),
        R2EX("24", 3812.0),
        R0("30", 1000.0),
        L6("60", 3812.0),
        Lively("61", 3812.0),
        V5("50", 3812.0),
        V5PLUS("51", 3812.0),
        V5F("52", 3812.0),
        V5D("53", 3812.0),
        V8("80", 3812.0),
        V8F("86", 3812.0),
        V8S("87", 3812.0),
        Glide3("85", 3812.0),
        V10S("100", 3812.0),
        V10SF("101", 3812.0),
        V10("140", 3812.0),
        V10F("141", 3812.0),
        V10T("142", 3812.0),
        V10FT("143", 3812.0),
        UNKNOWN("x", 3812.0);

        /**
         * Check if this model belongs to a specific input type (first digit of ID).
         */
        fun belongsToInputType(type: String): Boolean {
            return if (type == "0") {
                id.length == 1
            } else {
                id.length == 2 && id.startsWith(type)
            }
        }

        companion object {
            /**
             * Find model by its ID string.
             */
            fun findById(id: String): Model {
                return entries.find { it.id == id } ?: UNKNOWN
            }

            /**
             * Find model from slow info data bytes.
             */
            fun findByBytes(data: ByteArray): Model {
                if (data.size < 108) return UNKNOWN

                val idBuilder = StringBuilder()
                if (data[107] > 0) {
                    idBuilder.append(data[107].toInt())
                }
                idBuilder.append(data[104].toInt())

                return findById(idBuilder.toString())
            }
        }
    }

    // ==================== CAN Message ID Values ====================

    /**
     * CAN message ID values used in InMotion protocol.
     */
    enum class IDValue(val value: Int) {
        NoOp(0),
        GetFastInfo(0x0F550113),
        GetSlowInfo(0x0F550114),
        RideMode(0x0F550115),
        RemoteControl(0x0F550116),
        Calibration(0x0F550119),
        PinCode(0x0F550307),
        Light(0x0F55010D),
        HandleButton(0x0F55012E),
        SpeakerVolume(0x0F55060A),
        PlaySound(0x0F550609),
        Alert(0x0F780101);

        companion object {
            fun fromInt(value: Int): IDValue {
                return entries.find { it.value == value } ?: NoOp
            }
        }
    }

    // ==================== CAN Message ====================

    /**
     * CAN message structure for InMotion protocol.
     */
    class CANMessage private constructor() {

        var id: Int = IDValue.NoOp.value
        var data: ByteArray = ByteArray(8)
        var len: Int = 0
        var ch: Int = 0
        var format: Int = 0 // 0 = StandardFormat, 1 = ExtendedFormat
        var type: Int = 0   // 0 = DataFrame, 1 = RemoteFrame
        var exData: ByteArray? = null

        /**
         * Check if this is a valid extended message.
         */
        fun isValid(): Boolean = exData != null

        /**
         * Write the CAN message to a byte buffer with framing.
         */
        fun writeBuffer(): ByteArray {
            val canBuffer = getBytes()
            val check = computeChecksum(canBuffer)

            val out = mutableListOf<Byte>()
            out.add(0xAA.toByte())
            out.add(0xAA.toByte())

            // Add escaped data
            for (b in canBuffer) {
                val unsigned = b.toInt() and 0xFF
                if (unsigned == 0xAA || unsigned == 0x55 || unsigned == 0xA5) {
                    out.add(0xA5.toByte())
                }
                out.add(b)
            }

            out.add(check)
            out.add(0x55)
            out.add(0x55)

            return out.toByteArray()
        }

        private fun getBytes(): ByteArray {
            val buff = mutableListOf<Byte>()

            // ID in little-endian (4 bytes)
            buff.add((id and 0xFF).toByte())
            buff.add(((id shr 8) and 0xFF).toByte())
            buff.add(((id shr 16) and 0xFF).toByte())
            buff.add(((id shr 24) and 0xFF).toByte())

            // Data (8 bytes)
            buff.addAll(data.toList())

            // Length, channel, format, type
            buff.add(len.toByte())
            buff.add(ch.toByte())
            buff.add(if (format == 0) 0 else 1)
            buff.add(if (type == 0) 0 else 1)

            // Extended data if present
            if (len == 0xFE && exData != null) {
                buff.addAll(exData!!.toList())
            }

            return buff.toByteArray()
        }

        // ==================== Message Parsing ====================

        data class FastInfoResult(
            val state: WheelState,
            val news: String? = null
        )

        /**
         * Parse fast info (telemetry) message.
         */
        fun parseFastInfoMessage(model: Model, currentState: WheelState, config: DecoderConfig): FastInfoResult? {
            val exData = this.exData ?: return null
            if (exData.size < 76) return null  // Need at least 76 bytes (largest offset: 72 + 4)

            val angle = ByteUtils.intFromBytesLE(exData, 0) / 65536.0
            var roll = ByteUtils.intFromBytesLE(exData, 72) / 90.0

            // Calculate speed from two motor values
            val motor1 = ByteUtils.intFromBytesLE(exData, 12)
            val motor2 = ByteUtils.intFromBytesLE(exData, 16)
            var speed = (motor1.toDouble() + motor2.toDouble()) / (model.speedCalculationFactor * 2.0)
            speed = abs(speed)

            val voltage = ByteUtils.intFromBytesLE(exData, 24)
            val current = ByteUtils.signedIntFromBytesLE(exData, 20).toInt()
            val temperature = exData[32].toInt()
            val temperature2 = exData[34].toInt()
            val battery = batteryFromVoltage(voltage, model, config.useCustomPercents)

            // Calculate distances based on model
            val totalDistance: Long
            val distance: Long

            when {
                model.belongsToInputType("1") || model.belongsToInputType("5") ||
                        model == Model.V8 || model == Model.Glide3 ||
                        model == Model.V10 || model == Model.V10F ||
                        model == Model.V10S || model == Model.V10SF ||
                        model == Model.V10T || model == Model.V10FT ||
                        model == Model.V8F || model == Model.V8S -> {
                    totalDistance = ByteUtils.intFromBytesLE(exData, 44).toLong()
                }
                model == Model.R0 -> {
                    totalDistance = ByteUtils.longFromBytesLE(exData, 44)
                }
                model == Model.L6 -> {
                    totalDistance = ByteUtils.longFromBytesLE(exData, 44) * 100
                }
                else -> {
                    totalDistance = (ByteUtils.longFromBytesLE(exData, 44) / 5.711016379455429E7).toLong()
                }
            }
            distance = ByteUtils.intFromBytesLE(exData, 48).toLong()

            // Parse work mode
            val workModeInt = ByteUtils.intFromBytesLE(exData, 60)
            val workMode: String
            if (model == Model.V8F || model == Model.V8S ||
                model == Model.V10 || model == Model.V10F || model == Model.V10FT ||
                model == Model.V10S || model == Model.V10SF || model == Model.V10T) {
                roll = 0.0  // These models don't support roll
                workMode = getWorkModeString(workModeInt)
            } else {
                workMode = getLegacyWorkModeString(workModeInt)
            }

            val newState = currentState.copy(
                angle = angle,
                roll = roll,
                speed = (speed * 360.0).roundToInt(),
                voltage = voltage,
                batteryLevel = battery,
                current = current,
                totalDistance = totalDistance,
                wheelDistance = distance,
                temperature = temperature * 100,
                imuTemp = temperature2,
                modeStr = workMode,
                wheelType = WheelType.INMOTION
            )

            return FastInfoResult(newState)
        }

        data class AlertResult(
            val state: WheelState,
            val news: String?
        )

        /**
         * Parse alert info message.
         */
        fun parseAlertInfoMessage(currentState: WheelState): AlertResult? {
            val alertId = data[0].toInt() and 0xFF
            val alertValue = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            val alertValue2 = ((data[7].toInt() and 0xFF) shl 24) or
                    ((data[6].toInt() and 0xFF) shl 16) or
                    ((data[5].toInt() and 0xFF) shl 8) or
                    (data[4].toInt() and 0xFF)
            val alertSpeed = abs((alertValue2 / 3812.0) * 3.6)

            val fullText = when (alertId) {
                0x05 -> "Start from tilt angle ${ByteUtils.formatDecimal(alertValue / 100.0)} at speed ${ByteUtils.formatDecimal(alertSpeed)}"
                0x06 -> "Tiltback at speed ${ByteUtils.formatDecimal(alertSpeed)} at limit ${ByteUtils.formatDecimal(alertValue / 1000.0)}"
                0x19 -> "Fall Down"
                0x20 -> "Low battery at voltage ${ByteUtils.formatDecimal(alertValue2 / 100.0)}"
                0x21 -> "Speed cut-off at speed ${ByteUtils.formatDecimal(alertSpeed)} and something ${ByteUtils.formatDecimal(alertValue / 10.0)}"
                0x26 -> "High load at speed ${ByteUtils.formatDecimal(alertSpeed)} and current ${ByteUtils.formatDecimal(alertValue / 1000.0)}"
                0x1d -> "Please repair: bad battery cell found. At voltage ${ByteUtils.formatDecimal(alertValue2 / 100.0)}"
                else -> "Unknown Alert: ID=$alertId value=$alertValue value2=$alertValue2"
            }

            val newState = currentState.copy(alert = fullText)
            return AlertResult(newState, fullText)
        }

        data class SlowInfoResult(
            val state: WheelState,
            val detectedModel: Model,
            val settings: SlowInfoSettings? = null
        )

        data class SlowInfoSettings(
            val light: Boolean,
            val led: Boolean,
            val handleButton: Boolean,
            val maxSpeed: Int,
            val speakerVolume: Int,
            val pedalAdjustment: Int,
            val rideMode: Boolean,
            val pedalHardness: Int
        )

        /**
         * Parse slow info (device info) message.
         */
        fun parseSlowInfoMessage(currentState: WheelState): SlowInfoResult? {
            val exData = this.exData ?: return null

            var detectedModel = Model.findByBytes(exData)
            if (detectedModel == Model.UNKNOWN) {
                detectedModel = Model.V8  // Default fallback
            }

            // Parse version (bytes 24-27)
            val v0 = exData[27].toInt() and 0xFF
            val v1 = exData[26].toInt() and 0xFF
            val v2 = ((exData[25].toInt() and 0xFF) shl 8) or (exData[24].toInt() and 0xFF)
            val version = "$v0.$v1.$v2"

            // Parse serial number (bytes 0-7, reversed hex)
            val serialNumber = StringBuilder()
            for (j in 0 until 8) {
                serialNumber.append(ByteUtils.formatHex(exData[7 - j]))
            }

            // Parse settings
            val light = exData[80] == 1.toByte()
            var led = false
            var handleButton = false
            var rideMode = false
            var pedalHardness = 100
            var speakerVolume = 0

            val maxSpeed = (((exData[61].toInt() and 0xFF) shl 8) or (exData[60].toInt() and 0xFF)) / 1000
            val pedalAdjustment = (ByteUtils.intFromBytesLE(exData, 56) / 6553.6).roundToInt()

            if (exData.size > 126) {
                speakerVolume = (((exData[126].toInt() and 0xFF) shl 8) or (exData[125].toInt() and 0xFF)) / 100
            }
            if (exData.size > 130) {
                led = exData[130] == 1.toByte()
            }
            if (exData.size > 129) {
                handleButton = exData[129] != 1.toByte()
            }
            if (exData.size > 132) {
                rideMode = exData[132] == 1.toByte()
            }
            if (exData.size > 124) {
                pedalHardness = (exData[124].toInt() - 28) and 0xFF
            }

            val newState = currentState.copy(
                serialNumber = serialNumber.toString(),
                model = getModelString(detectedModel),
                version = version,
                wheelType = WheelType.INMOTION
            )

            val settings = SlowInfoSettings(
                light = light,
                led = led,
                handleButton = handleButton,
                maxSpeed = maxSpeed,
                speakerVolume = speakerVolume,
                pedalAdjustment = pedalAdjustment,
                rideMode = rideMode,
                pedalHardness = pedalHardness
            )

            return SlowInfoResult(newState, detectedModel, settings)
        }

        companion object {
            /**
             * Verify and parse a CAN message from raw buffer.
             */
            fun verify(buffer: ByteArray): CANMessage? {
                // Check header and footer
                if (buffer.size < 5) return null
                if (buffer[0] != 0xAA.toByte() || buffer[1] != 0xAA.toByte()) return null
                if (buffer[buffer.size - 1] != 0x55.toByte() || buffer[buffer.size - 2] != 0x55.toByte()) return null

                // Extract data (remove header, checksum, footer)
                val dataEnd = buffer.size - 3
                val dataBuffer = buffer.copyOfRange(2, dataEnd)

                // Verify checksum
                val calculatedCheck = computeChecksum(dataBuffer)
                val packetCheck = buffer[dataEnd]

                if (calculatedCheck != packetCheck) {
                    return null
                }

                // Parse CAN message
                if (dataBuffer.size < 16) return null

                val msg = CANMessage()

                // ID (4 bytes, little-endian)
                msg.id = ((dataBuffer[3].toInt() and 0xFF) shl 24) or
                        ((dataBuffer[2].toInt() and 0xFF) shl 16) or
                        ((dataBuffer[1].toInt() and 0xFF) shl 8) or
                        (dataBuffer[0].toInt() and 0xFF)

                // Data (8 bytes)
                msg.data = dataBuffer.copyOfRange(4, 12)

                // Length, channel, format, type
                msg.len = dataBuffer[12].toInt() and 0xFF
                msg.ch = dataBuffer[13].toInt() and 0xFF
                msg.format = if (dataBuffer[14] == 0.toByte()) 0 else 1
                msg.type = if (dataBuffer[15] == 0.toByte()) 0 else 1

                // Extended data (if len == 0xFE)
                if (msg.len == 0xFE) {
                    val extDataLen = ByteUtils.intFromBytesLE(msg.data, 0)
                    if (extDataLen == dataBuffer.size - 16) {
                        msg.exData = dataBuffer.copyOfRange(16, 16 + extDataLen)
                    }
                }

                return msg
            }

            /**
             * Compute checksum for data buffer.
             */
            private fun computeChecksum(buffer: ByteArray): Byte {
                var check = 0
                for (c in buffer) {
                    check = (check + (c.toInt() and 0xFF)) and 0xFF
                }
                return check.toByte()
            }

            /**
             * Create a standard keep-alive message.
             */
            fun standardMessage(): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.GetFastInfo.value
                msg.ch = 5
                msg.data = byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)
                return msg
            }

            /**
             * Create a slow data request message.
             */
            fun getSlowData(): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.GetSlowInfo.value
                msg.ch = 5
                msg.type = 1 // RemoteFrame
                msg.data = byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)
                return msg
            }

            /**
             * Create a password message.
             */
            fun getPassword(password: String): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.PinCode.value
                msg.ch = 5
                msg.type = 0 // DataFrame

                val passBytes = password.encodeToByteArray()
                msg.data = ByteArray(8)
                for (i in 0 until minOf(6, passBytes.size)) {
                    msg.data[i] = passBytes[i]
                }
                return msg
            }

            /**
             * Create a light toggle message.
             */
            fun setLight(on: Boolean): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.Light.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    if (on) 1 else 0, 0, 0, 0, 0, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a LED toggle message.
             */
            fun setLed(on: Boolean): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.RemoteControl.value
                msg.ch = 5
                msg.type = 0
                val enable: Byte = if (on) 0x0F else 0x10
                msg.data = byteArrayOf(
                    0xB2.toByte(), 0, 0, 0, enable, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a beep/sound message.
             */
            fun wheelBeep(): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.RemoteControl.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    0xB2.toByte(), 0, 0, 0, 0x11, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a play sound message.
             */
            fun playSound(soundNumber: Byte): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.PlaySound.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(soundNumber, 0, 0, 0, 0, 0, 0, 0)
                return msg
            }

            /**
             * Create a power off message.
             */
            fun powerOff(): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.RemoteControl.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(0xB2.toByte(), 0, 0, 0, 5, 0, 0, 0)
                return msg
            }

            /**
             * Create a calibration message.
             */
            fun wheelCalibration(): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.Calibration.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    0x32, 0x54, 0x76, 0x98.toByte(), 0, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a max speed setting message.
             */
            fun setMaxSpeed(maxSpeed: Int): CANMessage {
                val msg = CANMessage()
                val value = (maxSpeed * 1000).toShort()
                val valueBytes = ByteUtils.getBytes(value)
                msg.len = 8
                msg.id = IDValue.RideMode.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(1, 0, 0, 0, valueBytes[1], valueBytes[0], 0, 0)
                return msg
            }

            /**
             * Create a handle button setting message.
             */
            fun setHandleButton(on: Boolean): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.HandleButton.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    if (on) 0 else 1, 0, 0, 0, 0, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a speaker volume setting message.
             */
            fun setSpeakerVolume(volume: Int): CANMessage {
                val msg = CANMessage()
                val scaledVolume = volume * 100
                msg.len = 8
                msg.id = IDValue.SpeakerVolume.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    (scaledVolume and 0xFF).toByte(),
                    ((scaledVolume shr 8) and 0xFF).toByte(),
                    0, 0, 0, 0, 0, 0
                )
                return msg
            }

            /**
             * Create a ride mode setting message.
             * @param classic true for Classic mode, false for Comfort mode
             */
            fun setRideMode(classic: Boolean): CANMessage {
                val msg = CANMessage()
                msg.len = 8
                msg.id = IDValue.RideMode.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(
                    0x0a, 0, 0, 0,
                    if (classic) 1 else 0,
                    0, 0, 0
                )
                return msg
            }

            /**
             * Create a pedal sensitivity setting message.
             */
            fun setPedalSensitivity(sensitivity: Int): CANMessage {
                val msg = CANMessage()
                val value = ((sensitivity + 28) shl 5).toShort()
                val valueBytes = ByteUtils.getBytes(value)
                msg.len = 8
                msg.id = IDValue.RideMode.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(0x06, 0, 0, 0, valueBytes[1], valueBytes[0], 0, 0)
                return msg
            }

            /**
             * Create a tilt horizon (pedal angle) setting message.
             */
            fun setTiltHorizon(tiltHorizon: Int): CANMessage {
                val msg = CANMessage()
                val tilt = tiltHorizon * 65536 / 10
                val t = ByteUtils.getBytes(tilt)
                msg.len = 8
                msg.id = IDValue.RideMode.value
                msg.ch = 5
                msg.type = 0
                msg.data = byteArrayOf(0, 0, 0, 0, t[3], t[2], t[1], t[0])
                return msg
            }
        }
    }

    // ==================== Utility Functions ====================

    companion object {
        /**
         * Calculate battery percentage from voltage based on model.
         */
        fun batteryFromVoltage(voltsInt: Int, model: Model, useBetterPercents: Boolean): Int {
            val volts = voltsInt / 100.0
            val batt: Double

            when {
                // R1 series and R0: 82.5V max, 68V min
                model.belongsToInputType("1") || model == Model.R0 -> {
                    batt = when {
                        volts >= 82.50 -> 1.0
                        volts > 68.0 -> (volts - 68.0) / 14.50
                        else -> 0.0
                    }
                }

                // V5 series, V8 series: 84V max with better percents
                model.belongsToInputType("5") || model == Model.V8 || model == Model.Glide3 ||
                        model == Model.V8F || model == Model.V8S -> {
                    batt = if (useBetterPercents) {
                        when {
                            volts > 84.00 -> 1.0
                            volts > 68.5 -> (volts - 68.5) / 15.5
                            else -> 0.0
                        }
                    } else {
                        when {
                            volts > 82.50 -> 1.0
                            volts > 68.0 -> (volts - 68.0) / 14.5
                            else -> 0.0
                        }
                    }
                }

                // V10 series: Better percentage calculation
                model == Model.V10 || model == Model.V10F || model == Model.V10S ||
                        model == Model.V10SF || model == Model.V10T || model == Model.V10FT -> {
                    batt = if (useBetterPercents) {
                        when {
                            volts > 83.50 -> 1.00
                            volts > 68.00 -> (volts - 66.50) / 17
                            volts > 64.00 -> (volts - 64.00) / 45
                            else -> 0.0
                        }
                    } else {
                        when {
                            volts > 82.50 -> 1.0
                            volts > 68.0 -> (volts - 68.0) / 14.5
                            else -> 0.0
                        }
                    }
                }

                // L6: Always 0
                model.belongsToInputType("6") -> {
                    batt = 0.0
                }

                // Default calculation (R-series, etc.)
                else -> {
                    batt = when {
                        volts >= 82.00 -> 1.0
                        volts > 77.8 -> ((volts - 77.8) / 4.2) * 0.2 + 0.8
                        volts > 74.8 -> ((volts - 74.8) / 3.0) * 0.2 + 0.6
                        volts > 71.8 -> ((volts - 71.8) / 3.0) * 0.2 + 0.4
                        volts > 70.3 -> ((volts - 70.3) / 1.5) * 0.2 + 0.2
                        volts > 68.0 -> ((volts - 68.0) / 2.3) * 0.2
                        else -> 0.0
                    }
                }
            }

            return (batt * 100.0).roundToInt()
        }

        /**
         * Get legacy work mode string.
         */
        private fun getLegacyWorkModeString(value: Int): String {
            return when (value and 0xF) {
                0 -> "Idle"
                1 -> "Drive"
                2 -> "Zero"
                3 -> "LargeAngle"
                4 -> "Check"
                5 -> "Lock"
                6 -> "Error"
                7 -> "Carry"
                8 -> "RemoteControl"
                9 -> "Shutdown"
                10 -> "PomStop"
                12 -> "Unlock"
                else -> "Unknown"
            }
        }

        /**
         * Get work mode string for newer wheels.
         */
        private fun getWorkModeString(value: Int): String {
            val hValue = value shr 4
            var result = when (hValue) {
                1 -> "Shutdown"
                2 -> "Drive"
                3 -> "Charging"
                else -> "Unknown code $hValue"
            }
            if ((value and 0xF) == 1) {
                result += " - Engine off"
            }
            return result
        }

        /**
         * Get human-readable model name.
         */
        fun getModelString(model: Model): String {
            return when (model.id) {
                "0" -> "InMotion R1N"
                "1" -> "InMotion R1S"
                "2" -> "InMotion R1CF"
                "3" -> "InMotion R1AP"
                "4" -> "InMotion R1EX"
                "5" -> "InMotion R1Sample"
                "6" -> "InMotion R1T"
                "7" -> "InMotion R10"
                "10" -> "InMotion V3"
                "11" -> "InMotion V3C"
                "12" -> "InMotion V3PRO"
                "13" -> "InMotion V3S"
                "21" -> "InMotion R2N"
                "22" -> "InMotion R2S"
                "23" -> "InMotion R2Sample"
                "20" -> "InMotion R2"
                "24" -> "InMotion R2EX"
                "30" -> "InMotion R0"
                "60" -> "InMotion L6"
                "61" -> "InMotion Lively"
                "50" -> "InMotion V5"
                "51" -> "InMotion V5PLUS"
                "52" -> "InMotion V5F"
                "53" -> "InMotion V5D"
                "80" -> "InMotion V8"
                "85" -> "Solowheel Glide 3"
                "86" -> "InMotion V8F"
                "87" -> "InMotion V8S"
                "100" -> "InMotion V10S"
                "101" -> "InMotion V10SF"
                "140" -> "InMotion V10"
                "141" -> "InMotion V10F"
                "142" -> "InMotion V10T"
                "143" -> "InMotion V10FT"
                else -> "Unknown"
            }
        }
    }
}
