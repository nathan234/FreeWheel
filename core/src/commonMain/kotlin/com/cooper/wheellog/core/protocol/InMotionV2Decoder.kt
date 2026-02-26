package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.utils.ByteUtils
import com.cooper.wheellog.core.utils.Lock
import com.cooper.wheellog.core.utils.withLock
import kotlin.math.roundToInt

/**
 * InMotion V2 protocol decoder.
 *
 * Supports InMotion wheels with V2 BLE protocol:
 * - V11, V11Y
 * - V12HS, V12HT, V12PRO, V12S
 * - V13, V13PRO
 * - V14g, V14s
 * - V9
 *
 * Frame format:
 * - Header: AA AA
 * - Flags: 0x11 (Initial) or 0x14 (Default)
 * - Length: Data length + 1
 * - Command: Command byte (masked with 0x7F for base command)
 * - Data: Variable length payload
 * - Checksum: XOR of all bytes from flags to end of data
 *
 * MAIN_INFO (0x02) sub-types (in data[0]):
 *   0x01 = Car type (model detection)
 *   0x02 = Serial number
 *   0x06 = Software/hardware versions
 *
 * Keep-alive sends REAL_TIME_INFO on every tick, interleaving init retries on every
 * 4th tick until model/serial/version are resolved. This ensures live data flows
 * immediately even if the wheel doesn't respond to INITIAL handshake messages.
 *
 * Response bit: Response frames have command byte OR'd with 0x80
 * (e.g., SETTINGS 0x20 → response 0xA0). Mask with 0x7F to get the base command.
 *
 * This class is thread-safe.
 */
class InMotionV2Decoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.INMOTION_V2
    override val keepAliveIntervalMs: Long = 250L

    private val stateLock = Lock()
    private val unpacker = InMotionV2Unpacker()
    private var model = Model.UNKNOWN
    private var protoVer = 0
    private var serialNumber = ""
    private var version = ""
    private var isModelDetected = false
    private var hasReceivedTelemetry = false
    private var keepAliveCounter = 0

    /**
     * InMotion V2 wheel models.
     */
    enum class Model(val id: Int, val displayName: String, val maxSpeed: Int, val cellCount: Int) {
        V11(61, "InMotion V11", 60, 20),
        V11Y(62, "InMotion V11y", 120, 20),
        V12HS(71, "InMotion V12 HS", 70, 24),
        V12HT(72, "InMotion V12 HT", 70, 24),
        V12PRO(73, "InMotion V12 PRO", 70, 24),
        V13(81, "InMotion V13", 120, 30),
        V13PRO(82, "InMotion V13 PRO", 120, 30),
        V14g(91, "InMotion V14 50GB", 120, 32),
        V14s(92, "InMotion V14 50S", 120, 32),
        V12S(111, "InMotion V12S", 120, 20),
        V9(121, "InMotion V9", 120, 20),
        P6(101, "InMotion P6", 150, 32),
        UNKNOWN(0, "InMotion Unknown", 100, 20);

        companion object {
            /**
             * Find model by series and type IDs.
             * The full ID is series * 10 + type.
             */
            fun findById(series: Int, type: Int): Model {
                val fullId = series * 10 + type
                return entries.find { it.id == fullId } ?: UNKNOWN
            }
        }
    }

    /**
     * Message flags indicating the type of message.
     */
    object Flag {
        const val INITIAL = 0x11
        const val DEFAULT = 0x14
    }

    /**
     * Command types for V2 protocol.
     */
    object Command {
        const val MAIN_VERSION = 0x01
        const val MAIN_INFO = 0x02
        const val DIAGNOSTIC = 0x03
        const val REAL_TIME_INFO = 0x04
        const val BATTERY_REAL_TIME_INFO = 0x05
        const val SOMETHING1 = 0x10
        const val TOTAL_STATS = 0x11
        const val SETTINGS = 0x20
        const val CONTROL = 0x60
    }

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? = stateLock.withLock {
        decodeFrames(data, unpacker, currentState) { buffer, state ->
            val msg = verifyAndParse(buffer) ?: return@decodeFrames null
            processMessage(msg, state)
        }
    }

    /**
     * Parsed message from the wheel.
     */
    private data class Message(
        val flags: Int,
        val len: Int,
        val command: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return flags == other.flags && len == other.len &&
                    command == other.command && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = flags
            result = 31 * result + len
            result = 31 * result + command
            result = 31 * result + data.contentHashCode()
            return result
        }
    }


    /**
     * Verify checksum and parse message from buffer.
     */
    private fun verifyAndParse(buffer: ByteArray): Message? {
        if (buffer.size < 5) return null

        // Calculate checksum (XOR of all bytes except header and checksum)
        val dataBuffer = buffer.copyOfRange(2, buffer.size - 1)
        var check = 0
        for (byte in dataBuffer) {
            check = (check xor (byte.toInt() and 0xFF)) and 0xFF
        }

        val bufferCheck = buffer[buffer.size - 1].toInt() and 0xFF
        if (check != bufferCheck) {
            return null
        }

        val flags = buffer[2].toInt() and 0xFF
        val len = buffer[3].toInt() and 0xFF
        val command = buffer[4].toInt() and 0x7F

        val messageData = if (len > 1 && buffer.size > 5) {
            buffer.copyOfRange(5, minOf(5 + len - 1, buffer.size))
        } else {
            ByteArray(0)
        }

        return Message(flags, len, command, messageData)
    }

    /**
     * Process a verified message and update state.
     */
    private fun processMessage(message: Message, currentState: WheelState): FrameResult? {
        return when {
            message.flags == Flag.INITIAL -> {
                when (message.command) {
                    Command.MAIN_INFO -> processMainInfo(message, currentState)
                    else -> null
                }
            }
            message.flags == Flag.DEFAULT -> {
                when (message.command) {
                    Command.SETTINGS -> processSettings(message, currentState)
                    Command.DIAGNOSTIC -> processDiagnostic(message, currentState)
                    Command.BATTERY_REAL_TIME_INFO -> processBatteryRealTimeInfo(message, currentState)
                    Command.TOTAL_STATS -> processTotalStats(message, currentState)
                    Command.REAL_TIME_INFO -> processRealTimeInfo(message, currentState)
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Process main info (car type, serial number, versions).
     */
    private fun processMainInfo(message: Message, currentState: WheelState): FrameResult? {
        val data = message.data
        if (data.isEmpty()) return null

        var newState = currentState

        when (data[0].toInt() and 0xFF) {
            0x01 -> {
                // Car type: data format: [01, mainSeries, series, type, batch, feature, reverse]
                if (message.len >= 6) {
                    val series = data[2].toInt() and 0xFF
                    val type = data[3].toInt() and 0xFF
                    model = Model.findById(series, type)
                    isModelDetected = true
                    newState = newState.copy(
                        model = model.displayName,
                        wheelType = WheelType.INMOTION_V2
                    )
                }
            }
            0x02 -> {
                // Serial number
                if (message.len >= 17) {
                    serialNumber = data.copyOfRange(1, 17).decodeToString()
                    newState = newState.copy(serialNumber = serialNumber)
                }
            }
            0x06 -> {
                // Versions
                if (message.len >= 24) {
                    protoVer = 0
                    val driverBoard3 = ByteUtils.shortFromBytesLE(data, 2)
                    val driverBoard2 = data[4].toInt() and 0xFF
                    val driverBoard1 = data[5].toInt() and 0xFF
                    val driverBoard = "$driverBoard1.$driverBoard2.$driverBoard3"

                    val mainBoard3 = ByteUtils.shortFromBytesLE(data, 11)
                    val mainBoard2 = data[13].toInt() and 0xFF
                    val mainBoard1 = data[14].toInt() and 0xFF
                    val mainBoard = "$mainBoard1.$mainBoard2.$mainBoard3"

                    val ble3 = ByteUtils.shortFromBytesLE(data, 20)
                    val ble2 = data[22].toInt() and 0xFF
                    val ble1 = data[23].toInt() and 0xFF
                    val ble = "$ble1.$ble2.$ble3"

                    version = "Main:$mainBoard Drv:$driverBoard BLE:$ble"
                    newState = newState.copy(version = version)

                    // Set protocol version for V11
                    if (model == Model.V11) {
                        protoVer = if (mainBoard1 < 2 && mainBoard2 < 4) 1 else 2
                    }
                }
            }
        }

        return FrameResult(newState, false)
    }

    /**
     * Process settings data.
     */
    private fun processSettings(message: Message, currentState: WheelState): FrameResult? {
        return when (model) {
            Model.V11 -> parseSettingsV11(message.data, currentState)
            Model.V11Y -> parseSettingsV11y(message.data, currentState)
            Model.V12HS, Model.V12HT, Model.V12PRO -> parseSettingsV12(message.data, currentState)
            Model.V13, Model.V13PRO -> parseSettingsV13(message.data, currentState)
            Model.V14g, Model.V14s -> parseSettingsV14(message.data, currentState)
            Model.V9 -> parseSettingsV9(message.data, currentState)
            Model.V12S -> parseSettingsV12S(message.data, currentState)
            else -> parseSettingsV13V14(message.data, currentState)
        }
    }

    /**
     * Process diagnostic data.
     */
    private fun processDiagnostic(message: Message, currentState: WheelState): FrameResult? {
        // Check if all diagnostic bytes are 0 (OK)
        if (message.data.size > 7) {
            for (byte in message.data) {
                if (byte.toInt() != 0) {
                    // Has diagnostic errors, but we don't modify state for now
                    return null
                }
            }
        }
        return null
    }

    /**
     * Process battery real-time info.
     */
    private fun processBatteryRealTimeInfo(message: Message, currentState: WheelState): FrameResult? {
        val data = message.data
        if (data.size < 20) return null

        // Parse battery data (not stored in WheelState currently)
        // val bat1Voltage = ByteUtils.shortFromBytesLE(data, 0)
        // val bat1Temp = data[4].toInt()
        // val bat2Voltage = ByteUtils.shortFromBytesLE(data, 8)
        // val bat2Temp = data[12].toInt()
        // val chargeVoltage = ByteUtils.shortFromBytesLE(data, 16)
        // val chargeCurrent = ByteUtils.shortFromBytesLE(data, 18)

        return null
    }

    /**
     * Process total statistics.
     */
    private fun processTotalStats(message: Message, currentState: WheelState): FrameResult? {
        val data = message.data
        if (data.size < 20) return null

        val totalDistance = ByteUtils.intFromBytesLE(data, 0).toLong() * 10

        return FrameResult(
            currentState.copy(totalDistance = totalDistance),
            false
        )
    }

    /**
     * Process real-time telemetry info.
     */
    private fun processRealTimeInfo(message: Message, currentState: WheelState): FrameResult? {
        val result = when (model) {
            Model.V11 -> {
                if (protoVer < 2) parseRealTimeInfoV11Old(message.data, currentState)
                else parseByLayout(Layouts.V11_1_4, message.data, currentState)
            }
            Model.V11Y, Model.V9, Model.V12S -> parseByLayout(Layouts.EXTENDED, message.data, currentState)
            Model.V12HS, Model.V12HT, Model.V12PRO -> parseByLayout(Layouts.V12, message.data, currentState)
            Model.V13, Model.V13PRO -> parseByLayout(Layouts.V13, message.data, currentState)
            Model.V14g, Model.V14s -> parseByLayout(Layouts.V14, message.data, currentState)
            else -> parseRealTimeInfoGeneric(message.data, currentState)
        }
        if (result?.hasNewData == true) {
            hasReceivedTelemetry = true
        }
        return result
    }

    // ==================== Layout-driven Real-Time Parsing ====================

    private enum class MileageType { SHORT_TIMES_10, INT_REV_LE }
    private enum class BatLevelType { SINGLE_BYTE_MASKED, SHORT_DIV_100, DUAL_SHORT_AVG }

    /**
     * Describes the byte-offset layout for a model's real-time telemetry frame.
     * Each field is the byte offset within the data payload.
     */
    private data class RealTimeLayout(
        val minSize: Int,
        val voltage: Int,
        val current: Int,
        val speed: Int,
        val torque: Int,
        val pwm: Int,
        val batPower: Int,
        val motPower: Int,
        val pitchAngle: Int,
        val rollAngle: Int,
        val mileage: Int,
        val batLevel: Int,
        val speedLimit: Int,
        val currentLimit: Int,
        val mosTemp: Int,
        val temp2: Int,
        val cpuTemp: Int,
        val imuTemp: Int,
        val modeString: Int,
        val error: Int,
        val mileageType: MileageType,
        val batLevelType: BatLevelType,
        val liftedByteOffset: Int
    )

    private object Layouts {
        val V11_1_4 = RealTimeLayout(
            minSize = 57, voltage = 0, current = 2, speed = 4, torque = 6, pwm = 8,
            batPower = 10, motPower = 12, pitchAngle = 16, rollAngle = 20,
            mileage = 26, batLevel = 28, speedLimit = 34, currentLimit = 36,
            mosTemp = 42, temp2 = 45, cpuTemp = 46, imuTemp = 47,
            modeString = 56, error = 61,
            mileageType = MileageType.SHORT_TIMES_10,
            batLevelType = BatLevelType.SHORT_DIV_100,
            liftedByteOffset = 1
        )
        val V12 = RealTimeLayout(
            minSize = 60, voltage = 0, current = 2, speed = 4, torque = 6, pwm = 8,
            batPower = 10, motPower = 12, pitchAngle = 16, rollAngle = 20,
            mileage = 22, batLevel = 24, speedLimit = 30, currentLimit = 32,
            mosTemp = 40, temp2 = 41, cpuTemp = 44, imuTemp = 45,
            modeString = 54, error = 59,
            mileageType = MileageType.SHORT_TIMES_10,
            batLevelType = BatLevelType.SHORT_DIV_100,
            liftedByteOffset = 1
        )
        val V13 = RealTimeLayout(
            minSize = 77, voltage = 0, current = 2, speed = 8, torque = 18, pwm = 14,
            batPower = 16, motPower = 22, pitchAngle = 6, rollAngle = 24,
            mileage = 10, batLevel = 34, speedLimit = 40, currentLimit = 50,
            mosTemp = 58, temp2 = 59, cpuTemp = 62, imuTemp = 63,
            modeString = 74, error = 76,
            mileageType = MileageType.INT_REV_LE,
            batLevelType = BatLevelType.DUAL_SHORT_AVG,
            liftedByteOffset = 1
        )
        val V14 = RealTimeLayout(
            minSize = 78, voltage = 0, current = 2, speed = 8, torque = 12, pwm = 14,
            batPower = 16, motPower = 18, pitchAngle = 20, rollAngle = 22,
            mileage = 28, batLevel = 34, speedLimit = 40, currentLimit = 50,
            mosTemp = 58, temp2 = 59, cpuTemp = 62, imuTemp = 63,
            modeString = 74, error = 77,
            mileageType = MileageType.SHORT_TIMES_10,
            batLevelType = BatLevelType.DUAL_SHORT_AVG,
            liftedByteOffset = 2
        )
        /** V11Y, V9, V12S — same offsets as V14 but standard lifted byte. */
        val EXTENDED = RealTimeLayout(
            minSize = 78, voltage = 0, current = 2, speed = 8, torque = 12, pwm = 14,
            batPower = 16, motPower = 18, pitchAngle = 20, rollAngle = 22,
            mileage = 28, batLevel = 34, speedLimit = 40, currentLimit = 50,
            mosTemp = 58, temp2 = 59, cpuTemp = 62, imuTemp = 63,
            modeString = 74, error = 77,
            mileageType = MileageType.SHORT_TIMES_10,
            batLevelType = BatLevelType.DUAL_SHORT_AVG,
            liftedByteOffset = 1
        )
    }

    /**
     * Shared parser that reads telemetry fields according to a [RealTimeLayout].
     */
    private fun parseByLayout(layout: RealTimeLayout, data: ByteArray, currentState: WheelState): FrameResult? {
        if (data.size < layout.minSize) return null

        val voltage = ByteUtils.shortFromBytesLE(data, layout.voltage)
        val current = ByteUtils.signedShortFromBytesLE(data, layout.current)
        val speed = ByteUtils.signedShortFromBytesLE(data, layout.speed)
        val torque = ByteUtils.signedShortFromBytesLE(data, layout.torque)
        val pwm = ByteUtils.signedShortFromBytesLE(data, layout.pwm)
        val batPower = ByteUtils.signedShortFromBytesLE(data, layout.batPower)
        val motPower = ByteUtils.signedShortFromBytesLE(data, layout.motPower)
        val pitchAngle = ByteUtils.signedShortFromBytesLE(data, layout.pitchAngle)
        val rollAngle = ByteUtils.signedShortFromBytesLE(data, layout.rollAngle)

        val mileage = when (layout.mileageType) {
            MileageType.SHORT_TIMES_10 -> (ByteUtils.shortFromBytesLE(data, layout.mileage) * 10).toLong()
            MileageType.INT_REV_LE -> ByteUtils.intFromBytesRevLE(data, layout.mileage)
        }
        val batLevel = when (layout.batLevelType) {
            BatLevelType.SHORT_DIV_100 -> (ByteUtils.shortFromBytesLE(data, layout.batLevel) / 100.0).roundToInt()
            BatLevelType.DUAL_SHORT_AVG -> {
                val b1 = ByteUtils.shortFromBytesLE(data, layout.batLevel)
                val b2 = ByteUtils.shortFromBytesLE(data, layout.batLevel + 2)
                ((b1 + b2) / 200.0).roundToInt()
            }
            BatLevelType.SINGLE_BYTE_MASKED -> data[layout.batLevel].toInt() and 0x7F
        }

        val mosTemp = decodeTemperature(data[layout.mosTemp])
        val temp2 = decodeTemperature(data[layout.temp2])
        val cpuTemp = decodeTemperature(data[layout.cpuTemp])
        val imuTemp = decodeTemperature(data[layout.imuTemp])
        val speedLimit = ByteUtils.shortFromBytesLE(data, layout.speedLimit)
        val currentLimit = ByteUtils.shortFromBytesLE(data, layout.currentLimit)
        val modeStr = buildModeString(data, layout.modeString, layout.liftedByteOffset)
        val alert = getErrorString(data, layout.error)

        return FrameResult(
            state = currentState.copy(
                voltage = voltage,
                current = current,
                speed = speed,
                torque = torque / 100.0,
                motorPower = motPower.toDouble(),
                power = batPower * 100,
                wheelDistance = mileage,
                batteryLevel = batLevel,
                temperature = mosTemp * 100,
                temperature2 = temp2 * 100,
                angle = pitchAngle / 100.0,
                roll = rollAngle / 100.0,
                speedLimit = speedLimit / 100.0,
                currentLimit = currentLimit / 100.0,
                cpuTemp = cpuTemp,
                imuTemp = imuTemp,
                output = pwm,
                calculatedPwm = pwm / 10000.0,
                modeStr = modeStr,
                alert = alert,
                model = model.displayName,
                wheelType = WheelType.INMOTION_V2
            ),
            hasNewData = true,
            news = alert.ifEmpty { null }
        )
    }

    // ==================== V11 Old Protocol Parsing ====================

    /**
     * V11 old protocol (protoVer < 2): unique field order and conditional state offset.
     * Not suitable for layout-driven parsing due to genuinely different structure.
     */
    private fun parseRealTimeInfoV11Old(data: ByteArray, currentState: WheelState): FrameResult? {
        if (data.size < 38) return null

        val voltage = ByteUtils.shortFromBytesLE(data, 0)
        val current = ByteUtils.signedShortFromBytesLE(data, 2)
        val speed = ByteUtils.signedShortFromBytesLE(data, 4)
        val torque = ByteUtils.signedShortFromBytesLE(data, 6)
        val batPower = ByteUtils.signedShortFromBytesLE(data, 8)
        val motPower = ByteUtils.signedShortFromBytesLE(data, 10)
        val mileage = ByteUtils.shortFromBytesLE(data, 12) * 10
        val batLevel = data[16].toInt() and 0x7F
        val mosTemp = decodeTemperature(data[17])
        val boardTemp = decodeTemperature(data[20])
        val pitchAngle = ByteUtils.signedShortFromBytesLE(data, 22)
        val rollAngle = ByteUtils.signedShortFromBytesLE(data, 26)
        val dynamicSpeedLimit = ByteUtils.shortFromBytesLE(data, 28)
        val dynamicCurrentLimit = ByteUtils.shortFromBytesLE(data, 30)
        val cpuTemp = decodeTemperature(data[34])
        val imuTemp = decodeTemperature(data[35])
        val pwm = ByteUtils.shortFromBytesLE(data, 36)

        // State data — conditional offset based on frame size
        val stateIndex = if (data.size < 49) 36 else 38
        val modeStr = buildModeString(data, stateIndex)
        val alert = getErrorString(data, stateIndex + 5)

        return FrameResult(
            state = currentState.copy(
                voltage = voltage,
                current = current,
                speed = speed,
                torque = torque / 100.0,
                motorPower = motPower.toDouble(),
                power = batPower * 100,
                wheelDistance = mileage.toLong(),
                batteryLevel = batLevel,
                temperature = mosTemp * 100,
                temperature2 = boardTemp * 100,
                angle = pitchAngle / 100.0,
                roll = rollAngle / 100.0,
                speedLimit = dynamicSpeedLimit / 100.0,
                currentLimit = dynamicCurrentLimit / 100.0,
                cpuTemp = cpuTemp,
                imuTemp = imuTemp,
                output = pwm,
                calculatedPwm = pwm / 10000.0,
                modeStr = modeStr,
                alert = alert,
                model = model.displayName,
                wheelType = WheelType.INMOTION_V2
            ),
            hasNewData = true,
            news = alert.ifEmpty { null }
        )
    }

    // ==================== Generic Fallback Parsing ====================

    /**
     * Generic fallback parser for unknown models.
     * Uses the V14/V11Y layout (newest known format) to extract universally common fields.
     * This ensures new/unknown wheels get at least basic telemetry (voltage, current, speed, PWM).
     */
    private fun parseRealTimeInfoGeneric(data: ByteArray, currentState: WheelState): FrameResult? {
        // Need at least enough data for the core fields (V14/V11Y layout)
        if (data.size < 24) return null

        val voltage = ByteUtils.shortFromBytesLE(data, 0)
        val current = ByteUtils.signedShortFromBytesLE(data, 2)
        val speed = ByteUtils.signedShortFromBytesLE(data, 8)
        val torque = ByteUtils.signedShortFromBytesLE(data, 12)
        val pwm = ByteUtils.signedShortFromBytesLE(data, 14)
        val batPower = ByteUtils.signedShortFromBytesLE(data, 16)
        val motPower = ByteUtils.signedShortFromBytesLE(data, 18)
        val pitchAngle = ByteUtils.signedShortFromBytesLE(data, 20)
        val rollAngle = if (data.size > 23) ByteUtils.signedShortFromBytesLE(data, 22) else 0

        // Optional fields — only parse if data is long enough
        val mileage = if (data.size > 29) ByteUtils.shortFromBytesLE(data, 28) * 10 else 0
        val batLevel = if (data.size > 37) {
            val b1 = ByteUtils.shortFromBytesLE(data, 34)
            val b2 = ByteUtils.shortFromBytesLE(data, 36)
            ((b1 + b2) / 200.0).roundToInt()
        } else 0
        val dynamicSpeedLimit = if (data.size > 41) ByteUtils.shortFromBytesLE(data, 40) else 0
        val dynamicCurrentLimit = if (data.size > 51) ByteUtils.shortFromBytesLE(data, 50) else 0
        val mosTemp = if (data.size > 58) decodeTemperature(data[58]) else 0
        val motTemp = if (data.size > 59) decodeTemperature(data[59]) else 0
        val cpuTemp = if (data.size > 62) decodeTemperature(data[62]) else 0
        val imuTemp = if (data.size > 63) decodeTemperature(data[63]) else 0

        val displayName = if (model != Model.UNKNOWN) model.displayName else "InMotion Unknown"

        return FrameResult(
            state = currentState.copy(
                voltage = voltage,
                current = current,
                speed = speed,
                torque = torque / 100.0,
                motorPower = motPower.toDouble(),
                power = batPower * 100,
                wheelDistance = mileage.toLong(),
                batteryLevel = batLevel,
                temperature = mosTemp * 100,
                temperature2 = motTemp * 100,
                angle = pitchAngle / 100.0,
                roll = rollAngle / 100.0,
                speedLimit = if (dynamicSpeedLimit > 0) dynamicSpeedLimit / 100.0 else currentState.speedLimit,
                currentLimit = if (dynamicCurrentLimit > 0) dynamicCurrentLimit / 100.0 else currentState.currentLimit,
                cpuTemp = cpuTemp,
                imuTemp = imuTemp,
                output = pwm,
                calculatedPwm = pwm / 10000.0,
                model = displayName,
                wheelType = WheelType.INMOTION_V2
            ),
            hasNewData = true
        )
    }

    // ==================== Settings Parsing ====================

    private fun parseSettingsV11(data: ByteArray, currentState: WheelState): FrameResult? {
        // V11 unique layout: offsets relative to data[1]
        val i = 1
        if (data.size < i + 23) return null

        val speedLim = ByteUtils.shortFromBytesLE(data, i) / 100
        val pedalTilt = ByteUtils.signedShortFromBytesLE(data, i + 2) / 10
        val driveMode = (data[i + 4].toInt() and 0x0F) != 0  // low nibble
        val rideModeRaw = (data[i + 4].toInt() and 0xFF) shr 4  // high nibble
        val fancier = rideModeRaw != 0
        val comfSens = data[i + 5].toInt() and 0xFF
        val classSens = data[i + 6].toInt() and 0xFF
        val sensitivity = if (rideModeRaw != 0) classSens else comfSens
        val volume = data[i + 7].toInt() and 0xFF
        val lightBr = if (data.size > i + 17) data[i + 17].toInt() and 0xFF else -1

        // Bit-packed flags at data[i+20]
        val flags20 = if (data.size > i + 20) data[i + 20].toInt() and 0xFF else 0
        val audioState = flags20 and 0x03           // bits 0-1
        val decorState = (flags20 shr 2) and 0x03   // bits 2-3 → DRL
        val liftedState = (flags20 shr 4) and 0x03  // bits 4-5 → handle button (inverted)

        // Bit-packed flags at data[i+21]
        val flags21 = if (data.size > i + 21) data[i + 21].toInt() and 0xFF else 0
        val transpMode = ((flags21 shr 4) and 0x03) != 0  // bits 4-5

        // Bit-packed flags at data[i+22]
        val flags22 = if (data.size > i + 22) data[i + 22].toInt() and 0xFF else 0
        val lowBat = ((flags22 shr 2) and 0x03) != 0      // bits 2-3 → goHome
        val fanQuietMode = ((flags22 shr 4) and 0x03) != 0 // bits 4-5

        return FrameResult(
            state = currentState.copy(
                maxSpeed = speedLim,
                pedalTilt = pedalTilt,
                pedalSensitivity = sensitivity,
                rideMode = driveMode,
                fancierMode = fancier,
                speakerVolume = volume,
                mute = audioState == 0,
                handleButton = liftedState == 0,
                drl = decorState != 0,
                lightBrightness = lightBr,
                transportMode = transpMode,
                goHomeMode = lowBat,
                fanQuiet = fanQuietMode
            ),
            hasNewData = false
        )
    }

    private fun parseSettingsV12(data: ByteArray, currentState: WheelState): FrameResult? {
        // V12 uses absolute offsets (not i-relative)
        if (data.size < 42) return null

        val speedLim = ByteUtils.shortFromBytesLE(data, 9) / 100
        val pedalTilt = ByteUtils.signedShortFromBytesLE(data, 15) / 10
        val classicMode = (data[19].toInt() and 0x01) != 0
        val fancier = ((data[19].toInt() shr 4) and 0x01) != 0
        val comfSens = data[20].toInt() and 0xFF
        val classSens = data[21].toInt() and 0xFF
        val sensitivity = if (classicMode) classSens else comfSens
        val volume = data[22].toInt() and 0xFF

        // Bit-packed flags at data[39]
        val flags39 = data[39].toInt() and 0xFF
        val muteFlag = (flags39 and 0x01) == 0        // bit 0, inverted
        val handleBtn = ((flags39 shr 2) and 0x01) == 0  // bit 2, inverted
        val transpMode = ((flags39 shr 6) and 0x01) != 0 // bit 6

        return FrameResult(
            state = currentState.copy(
                maxSpeed = speedLim,
                pedalTilt = pedalTilt,
                pedalSensitivity = sensitivity,
                rideMode = classicMode,
                fancierMode = fancier,
                speakerVolume = volume,
                mute = muteFlag,
                handleButton = handleBtn,
                transportMode = transpMode
            ),
            hasNewData = false
        )
    }

    /**
     * Parse settings for V13/V14 layout (shared).
     */
    private fun parseSettingsV13V14(data: ByteArray, currentState: WheelState): FrameResult? {
        val i = 1
        if (data.size < i + 35) return null

        val speedLim = ByteUtils.shortFromBytesLE(data, i) / 100
        val pedalTilt = ByteUtils.signedShortFromBytesLE(data, i + 8) / 10
        val offroad = (data[i + 10].toInt() and 0x01) != 0
        val fancier = ((data[i + 10].toInt() shr 4) and 0x01) != 0
        val comfSens = data[i + 11].toInt() and 0xFF
        val classSens = data[i + 12].toInt() and 0xFF
        val sensitivity = if (offroad) classSens else comfSens

        // Bit-packed flags at data[i+30]
        val flags30 = data[i + 30].toInt() and 0xFF
        val muteFlag = (flags30 and 0x01) == 0          // bit 0, inverted
        val drlFlag = ((flags30 shr 2) and 0x01) != 0   // bit 2

        // data[i+31]
        val transpMode = ((data[i + 31].toInt() shr 4) and 0x01) != 0  // bit 4

        return FrameResult(
            state = currentState.copy(
                maxSpeed = speedLim,
                pedalTilt = pedalTilt,
                pedalSensitivity = sensitivity,
                rideMode = offroad,
                fancierMode = fancier,
                mute = muteFlag,
                drl = drlFlag,
                transportMode = transpMode
            ),
            hasNewData = false
        )
    }

    private fun parseSettingsV13(data: ByteArray, currentState: WheelState): FrameResult? {
        return parseSettingsV13V14(data, currentState)
    }

    private fun parseSettingsV14(data: ByteArray, currentState: WheelState): FrameResult? {
        return parseSettingsV13V14(data, currentState)
    }

    /**
     * Parse settings for V11Y/V9/V12S layout (shared).
     * Same as V13/V14 plus handleButton, goHome.
     */
    private fun parseSettingsExtended(data: ByteArray, currentState: WheelState): FrameResult? {
        val i = 1
        if (data.size < i + 35) return null

        val speedLim = ByteUtils.shortFromBytesLE(data, i) / 100
        val pedalTilt = ByteUtils.signedShortFromBytesLE(data, i + 8) / 10
        val offroad = (data[i + 10].toInt() and 0x01) != 0
        val fancier = ((data[i + 10].toInt() shr 4) and 0x01) != 0
        val comfSens = data[i + 11].toInt() and 0xFF
        val classSens = data[i + 12].toInt() and 0xFF
        val sensitivity = if (offroad) classSens else comfSens

        // Bit-packed flags at data[i+30]
        val flags30 = data[i + 30].toInt() and 0xFF
        val muteFlag = (flags30 and 0x01) == 0           // bit 0, inverted
        val drlFlag = ((flags30 shr 2) and 0x01) != 0    // bit 2
        val handleBtn = ((flags30 shr 4) and 0x01) == 0  // bit 4, inverted

        // data[i+31]
        val transpMode = ((data[i + 31].toInt() shr 4) and 0x01) != 0  // bit 4

        // data[i+32]
        val goHome = ((data[i + 32].toInt() shr 2) and 0x01) != 0  // bit 2

        return FrameResult(
            state = currentState.copy(
                maxSpeed = speedLim,
                pedalTilt = pedalTilt,
                pedalSensitivity = sensitivity,
                rideMode = offroad,
                fancierMode = fancier,
                mute = muteFlag,
                drl = drlFlag,
                handleButton = handleBtn,
                transportMode = transpMode,
                goHomeMode = goHome
            ),
            hasNewData = false
        )
    }

    private fun parseSettingsV11y(data: ByteArray, currentState: WheelState): FrameResult? {
        return parseSettingsExtended(data, currentState)
    }

    private fun parseSettingsV9(data: ByteArray, currentState: WheelState): FrameResult? {
        return parseSettingsExtended(data, currentState)
    }

    private fun parseSettingsV12S(data: ByteArray, currentState: WheelState): FrameResult? {
        return parseSettingsExtended(data, currentState)
    }

    // ==================== Helper Functions ====================

    /**
     * Decode temperature from byte (offset encoding: value + 80 - 256).
     */
    private fun decodeTemperature(byte: Byte): Int {
        return (byte.toInt() and 0xFF) + 80 - 256
    }

    /**
     * Build mode string from state data.
     *
     * @param liftedByteOffset byte offset from [index] where the lifted-state bit lives.
     *   Standard (V11, V12, V13, V11Y, V9, V12S) = 1; V14 = 2.
     */
    private fun buildModeString(data: ByteArray, index: Int, liftedByteOffset: Int = 1): String {
        if (data.size <= index + liftedByteOffset) return ""

        val stateByte = data[index].toInt() and 0xFF
        val liftedByte = data[index + liftedByteOffset].toInt() and 0xFF

        val motState = (stateByte shr 6) and 0x01
        val chrgState = (stateByte shr 7) and 0x01
        val liftedState = (liftedByte shr 2) and 0x01

        return buildString {
            if (motState == 1) append("Active")
            if (chrgState == 1) append(" Charging")
            if (liftedState == 1) append(" Lifted")
        }.trim()
    }

    /**
     * Get error string from error bytes.
     */
    private fun getErrorString(data: ByteArray, index: Int): String {
        if (data.size < index + 7) return ""

        return buildString {
            // Byte 0 errors
            if ((data[index].toInt() and 0x01) == 1) append("err_iPhaseSensorState ")
            if ((data[index].toInt() shr 1 and 0x01) == 1) append("err_iBusSensorState ")
            if ((data[index].toInt() shr 2 and 0x01) == 1) append("err_motorHallState ")
            if ((data[index].toInt() shr 3 and 0x01) == 1) append("err_batteryState ")
            if ((data[index].toInt() shr 4 and 0x01) == 1) append("err_imuSensorState ")
            if ((data[index].toInt() shr 5 and 0x01) == 1) append("err_controllerCom1State ")
            if ((data[index].toInt() shr 6 and 0x01) == 1) append("err_controllerCom2State ")
            if ((data[index].toInt() shr 7 and 0x01) == 1) append("err_bleCom1State ")

            // Byte 1 errors
            if ((data[index + 1].toInt() and 0x01) == 1) append("err_bleCom2State ")
            if ((data[index + 1].toInt() shr 1 and 0x01) == 1) append("err_mosTempSensorState ")
            if ((data[index + 1].toInt() shr 2 and 0x01) == 1) append("err_motorTempSensorState ")
            if ((data[index + 1].toInt() shr 3 and 0x01) == 1) append("err_batteryTempSensorState ")
            if ((data[index + 1].toInt() shr 4 and 0x01) == 1) append("err_boardTempSensorState ")
            if ((data[index + 1].toInt() shr 5 and 0x01) == 1) append("err_fanState ")
            if ((data[index + 1].toInt() shr 6 and 0x01) == 1) append("err_rtcState ")
            if ((data[index + 1].toInt() shr 7 and 0x01) == 1) append("err_externalRomState ")

            // Byte 2 errors
            if ((data[index + 2].toInt() and 0x01) == 1) append("err_vBusSensorState ")
            if ((data[index + 2].toInt() shr 1 and 0x01) == 1) append("err_vBatterySensorState ")
            if ((data[index + 2].toInt() shr 2 and 0x01) == 1) append("err_canNotPowerOffState ")
            if ((data[index + 2].toInt() shr 3 and 0x01) == 1) append("err_notKnown1 ")

            // Byte 3 errors
            if ((data[index + 3].toInt() and 0x01) == 1) append("err_underVoltageState ")
            if ((data[index + 3].toInt() shr 1 and 0x01) == 1) append("err_overVoltageState ")
            val overBusCurrent = (data[index + 3].toInt() shr 2 and 0x03)
            if (overBusCurrent > 0) append("err_overBusCurrentState-$overBusCurrent ")
            val lowBattery = (data[index + 3].toInt() shr 4 and 0x03)
            if (lowBattery > 0) append("err_lowBatteryState-$lowBattery ")
            if ((data[index + 3].toInt() shr 6 and 0x01) == 1) append("err_mosTempState ")
            if ((data[index + 3].toInt() shr 7 and 0x01) == 1) append("err_motorTempState ")

            // Byte 4 errors
            if ((data[index + 4].toInt() and 0x01) == 1) append("err_batteryTempState ")
            if ((data[index + 4].toInt() shr 1 and 0x01) == 1) append("err_overBoardTempState ")
            if ((data[index + 4].toInt() shr 2 and 0x01) == 1) append("err_overSpeedState ")
            if ((data[index + 4].toInt() shr 3 and 0x01) == 1) append("err_outputSaturationState ")
            if ((data[index + 4].toInt() shr 4 and 0x01) == 1) append("err_motorSpinState ")
            if ((data[index + 4].toInt() shr 5 and 0x01) == 1) append("err_motorBlockState ")
            if ((data[index + 4].toInt() shr 6 and 0x01) == 1) append("err_postureState ")
            if ((data[index + 4].toInt() shr 7 and 0x01) == 1) append("err_riskBehaviourState ")

            // Byte 5 errors
            if ((data[index + 5].toInt() and 0x01) == 1) append("err_motorNoLoadState ")
            if ((data[index + 5].toInt() shr 1 and 0x01) == 1) append("err_noSelfTestState ")
            if ((data[index + 5].toInt() shr 2 and 0x01) == 1) append("err_compatibilityState ")
            if ((data[index + 5].toInt() shr 3 and 0x01) == 1) append("err_powerKeyLongPressState ")
            if ((data[index + 5].toInt() shr 4 and 0x01) == 1) append("err_forceDfuState ")
            if ((data[index + 5].toInt() shr 5 and 0x01) == 1) append("err_deviceLockState ")
            if ((data[index + 5].toInt() shr 6 and 0x01) == 1) append("err_cpuOverTempState ")
            if ((data[index + 5].toInt() shr 7 and 0x01) == 1) append("err_imuOverTempState ")

            // Byte 6 errors
            if ((data[index + 6].toInt() shr 1 and 0x01) == 1) append("err_hwCompatibilityState ")
            if ((data[index + 6].toInt() shr 2 and 0x01) == 1) append("err_fanLowSpeedState ")
            if ((data[index + 6].toInt() shr 3 and 0x01) == 1) append("err_notKnown2 ")
        }.trim()
    }

    override fun isReady(): Boolean = stateLock.withLock {
        hasReceivedTelemetry || (isModelDetected && version.isNotEmpty())
    }

    override fun reset() = stateLock.withLock {
        unpacker.reset()
        model = Model.UNKNOWN
        protoVer = 0
        serialNumber = ""
        version = ""
        isModelDetected = false
        hasReceivedTelemetry = false
        keepAliveCounter = 0
    }

    override fun buildCommand(command: WheelCommand): List<WheelCommand> {
        return when (command) {
            is WheelCommand.Beep -> listOf(
                WheelCommand.SendBytes(playBeepMessage(0x18))
            )
            is WheelCommand.SetLight -> listOf(
                WheelCommand.SendBytes(setLightMessage(command.enabled))
            )
            is WheelCommand.SetLock -> listOf(
                WheelCommand.SendBytes(setLockMessage(command.locked))
            )
            is WheelCommand.PowerOff -> listOf(
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.DIAGNOSTIC, byteArrayOf(0x81.toByte(), 0x00)))
            )
            is WheelCommand.Calibrate -> listOf(
                WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x42, 0x01, 0x00, 0x01)))
            )
            is WheelCommand.SetHandleButton -> {
                // Inverted logic: enabled=true sends 0, enabled=false sends 1
                val enable: Byte = if (command.enabled) 0 else 1
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2E, enable))))
            }
            is WheelCommand.SetRideMode -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x23, enable))))
            }
            is WheelCommand.SetSpeakerVolume -> listOf(
                WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x26, (command.volume and 0xFF).toByte())))
            )
            is WheelCommand.SetPedalTilt -> {
                val value = (command.angle * 10).toShort()
                val lo = (value.toInt() and 0xFF).toByte()
                val hi = ((value.toInt() shr 8) and 0xFF).toByte()
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x22, lo, hi))))
            }
            is WheelCommand.SetPedalSensitivity -> {
                val s = (command.sensitivity and 0xFF).toByte()
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x25, s, s))))
            }
            is WheelCommand.SetTransportMode -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x32, enable))))
            }
            is WheelCommand.SetDrl -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2D, enable))))
            }
            is WheelCommand.SetGoHomeMode -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x37, enable))))
            }
            is WheelCommand.SetFancierMode -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x24, enable))))
            }
            is WheelCommand.SetMute -> {
                // Inverted: mute=true sends 0, mute=false sends 1
                val enable: Byte = if (command.enabled) 0 else 1
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2C, enable))))
            }
            is WheelCommand.SetFanQuiet -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x38, enable))))
            }
            is WheelCommand.SetFan -> {
                val enable: Byte = if (command.enabled) 1 else 0
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x43, enable))))
            }
            is WheelCommand.SetLightBrightness -> listOf(
                WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2B, (command.value and 0xFF).toByte())))
            )
            is WheelCommand.SetMaxSpeed -> {
                val value = (command.speed * 100).toShort()
                val lo = (value.toInt() and 0xFF).toByte()
                val hi = ((value.toInt() shr 8) and 0xFF).toByte()
                listOf(WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x21, lo, hi))))
            }
            // InMotion V2 extended settings — TODO: fill command bytes from BLE capture
            is WheelCommand.SetBermAngleMode -> emptyList()
            is WheelCommand.SetBermAngle -> emptyList()
            is WheelCommand.SetTurningSensitivity -> emptyList()
            is WheelCommand.SetOnePedalMode -> emptyList()
            is WheelCommand.SetSpeedingBrakingMode -> emptyList()
            is WheelCommand.SetSpeedingBrakingAngle -> emptyList()
            is WheelCommand.SetSoundWave -> emptyList()
            is WheelCommand.SetSoundWaveSensitivity -> emptyList()
            is WheelCommand.SetSafeSpeedLimit -> emptyList()
            is WheelCommand.SetBackwardOverspeedAlert -> emptyList()
            is WheelCommand.SetTailLightMode -> emptyList()
            is WheelCommand.SetTurnSignalMode -> emptyList()
            is WheelCommand.SetLogoLightBrightness -> emptyList()
            is WheelCommand.SetAutoHeadlight -> emptyList()
            is WheelCommand.SetLightEffect -> emptyList()
            is WheelCommand.SetLightEffectMode -> emptyList()
            is WheelCommand.SetTwoBatteryMode -> emptyList()
            is WheelCommand.SetLowBatterySafeMode -> emptyList()
            is WheelCommand.SetSpinKill -> emptyList()
            is WheelCommand.SetCruise -> emptyList()
            is WheelCommand.SetLoadDetect -> emptyList()
            is WheelCommand.SetStandbyTime -> emptyList()
            is WheelCommand.SetChargeLimit -> emptyList()
            else -> emptyList()
        }
    }

    override fun getInitCommands(): List<WheelCommand> {
        return listOf(
            WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01))),
            WheelCommand.SendDelayed(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02)), 100),
            WheelCommand.SendDelayed(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06)), 200),
            WheelCommand.SendDelayed(buildMessage(Flag.DEFAULT, Command.SETTINGS, byteArrayOf(0x20)), 300),
            WheelCommand.SendDelayed(buildMessage(Flag.DEFAULT, Command.TOTAL_STATS, byteArrayOf()), 400)
        )
    }

    override fun getKeepAliveCommand(): WheelCommand = stateLock.withLock {
        keepAliveCounter++
        val needsInit = !isModelDetected || serialNumber.isEmpty() || version.isEmpty()
        if (needsInit && keepAliveCounter % 4 == 0) {
            // Retry whichever init step is still pending
            getNextInitRetryCommand()
        } else {
            // Always request live data — this is the primary keep-alive
            WheelCommand.SendBytes(buildMessage(Flag.DEFAULT, Command.REAL_TIME_INFO, byteArrayOf()))
        }
    }

    /**
     * Returns the next init command that still needs a response.
     */
    private fun getNextInitRetryCommand(): WheelCommand {
        return when {
            !isModelDetected ->
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01)))
            serialNumber.isEmpty() ->
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02)))
            else ->
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06)))
        }
    }

    // ==================== Message Building ====================

    companion object {
        /**
         * Build a message to send to the wheel.
         *
         * Escape encoding: bytes 0xAA and 0xA5 in the payload and checksum
         * are prefixed with 0xA5 to avoid being mistaken for frame headers.
         */
        fun buildMessage(flags: Int, command: Int, data: ByteArray): ByteArray {
            val buffer = mutableListOf<Byte>()

            // Add flags
            buffer.add(flags.toByte())
            // Add length (data length + 1 for command byte)
            buffer.add((data.size + 1).toByte())
            // Add command
            buffer.add(command.toByte())
            // Add data
            buffer.addAll(data.toList())

            // Calculate checksum
            var check = 0
            for (byte in buffer) {
                check = (check xor (byte.toInt() and 0xFF)) and 0xFF
            }

            // Build output with header and escape sequences
            val output = mutableListOf<Byte>()
            output.add(0xAA.toByte())
            output.add(0xAA.toByte())

            for (byte in buffer) {
                val b = byte.toInt() and 0xFF
                if (b == 0xAA || b == 0xA5) {
                    output.add(0xA5.toByte())
                }
                output.add(byte)
            }

            // Checksum must also be escaped
            val checkByte = check and 0xFF
            if (checkByte == 0xAA || checkByte == 0xA5) {
                output.add(0xA5.toByte())
            }
            output.add(check.toByte())

            return output.toByteArray()
        }

        /**
         * Create a message for requesting car type.
         */
        fun getCarTypeMessage(): ByteArray {
            return buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01))
        }

        /**
         * Create a message for requesting serial number.
         */
        fun getSerialNumberMessage(): ByteArray {
            return buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02))
        }

        /**
         * Create a message for requesting versions.
         */
        fun getVersionsMessage(): ByteArray {
            return buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06))
        }

        /**
         * Create a message for requesting current settings.
         */
        fun getCurrentSettingsMessage(): ByteArray {
            return buildMessage(Flag.DEFAULT, Command.SETTINGS, byteArrayOf(0x20))
        }

        /**
         * Create a message for requesting real-time data.
         */
        fun getRealTimeDataMessage(): ByteArray {
            return buildMessage(Flag.DEFAULT, Command.REAL_TIME_INFO, byteArrayOf())
        }

        /**
         * Create a message for requesting total stats.
         */
        fun getStatisticsMessage(): ByteArray {
            return buildMessage(Flag.DEFAULT, Command.TOTAL_STATS, byteArrayOf())
        }

        /**
         * Create a message to set light state.
         */
        fun setLightMessage(on: Boolean): ByteArray {
            val enable: Byte = if (on) 1 else 0
            return buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x50, enable))
        }

        /**
         * Create a message to set lock state.
         */
        fun setLockMessage(locked: Boolean): ByteArray {
            val enable: Byte = if (locked) 1 else 0
            return buildMessage(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x31, enable))
        }

        /**
         * Create a message to play a beep.
         */
        fun playBeepMessage(number: Int = 0x18): ByteArray {
            return buildMessage(
                Flag.DEFAULT,
                Command.CONTROL,
                byteArrayOf(0x51, (number and 0xFF).toByte(), 0x01)
            )
        }
    }
}
