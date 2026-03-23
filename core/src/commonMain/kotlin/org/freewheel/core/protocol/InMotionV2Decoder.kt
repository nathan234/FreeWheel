package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilityMap
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.SmartBms
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.domain.resolveAt
import org.freewheel.core.utils.ByteUtils
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
 * Thread-safe: All methods except [buildCommand] are called from the WCM
 * event loop (single-threaded). [buildCommand] reads model and firmware
 * version from the immutable [DecoderState] snapshot, so no lock is needed.
 */
class InMotionV2Decoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.INMOTION_V2
    override val keepAliveIntervalMs: Long = 250L

    private val unpacker = InMotionV2Unpacker()
    private var model = Model.UNKNOWN
    private var protoVer = 0
    private var serialNumber = ""
    private var version = ""
    private var mainBoardVersion = "" // e.g. "1.4.123" for firmware version checks
    private var isModelDetected = false
    private var hasReceivedTelemetry = false
    private var keepAliveCounter = 0
    private var bms1 = SmartBms()
    private var bms2 = SmartBms()
    private var bmsInitDone = false
    private var bmsPollCounter = 0

    /**
     * InMotion V2 wheel models.
     */
    enum class Model(
        val id: Int, val displayName: String, val maxSpeed: Int,
        val cellCount: Int, val batteryCount: Int = 1
    ) {
        V11(61, "InMotion V11", 60, 20),
        V11Y(62, "InMotion V11y", 120, 20),
        V12HS(71, "InMotion V12 HS", 70, 24),
        V12HT(72, "InMotion V12 HT", 70, 24),
        V12PRO(73, "InMotion V12 PRO", 70, 24),
        V13(81, "InMotion V13", 120, 30, batteryCount = 2),
        V13PRO(82, "InMotion V13 PRO", 120, 30, batteryCount = 2),
        V14g(91, "InMotion V14 50GB", 120, 32, batteryCount = 4),
        V14s(92, "InMotion V14 50S", 120, 32, batteryCount = 4),
        V12S(111, "InMotion V12S", 120, 20),
        V9(121, "InMotion V9", 120, 20),
        P6(131, "InMotion P6", 150, 56),
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

            /** Find model by its full ID (e.g. 61=V11, 131=P6). */
            fun findByFullId(id: Int): Model =
                entries.find { it.id == id } ?: UNKNOWN
        }
    }

    /**
     * Message flags indicating the type of message.
     */
    object Flag {
        const val INITIAL = 0x11
        const val DEFAULT = 0x14
        const val EXTENDED = 0x16
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

    /**
     * BMS battery IDs used in extended protocol (flag 0x16).
     * These appear as the command byte in BMS request/response frames.
     */
    object BmsId {
        const val BATTERY_1 = 0x24
        const val BATTERY_2 = 0x25
        const val BATTERY_3 = 0x26
        const val BATTERY_4 = 0x27

        /** BMS response sub-types (original request | 0x80). */
        const val RESP_STATUS = 0x81    // per-battery status (voltage, current, SOC, temps)
        const val RESP_VOLTAGES = 0x82  // per-battery cell voltages
        const val RESP_SERIAL = 0x84    // per-battery serial number + init
    }

    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
        val loopResult = decodeFrames(data, unpacker, currentState) { buffer, state ->
            val msg = verifyAndParse(buffer) ?: return@decodeFrames FrameOutcome.Unrecognized("verify_failed")
            val result = processMessage(msg, state)
            if (result != null) FrameOutcome.Processed(result) else FrameOutcome.Unrecognized("cmd=0x${msg.command.toString(16)} flags=0x${msg.flags.toString(16)}")
        }

        return when (loopResult) {
            is DecodeResult.Success -> {
                val bmsSnapshot = BmsState(bms1 = bms1.toSnapshot(), bms2 = bms2.toSnapshot())
                val resolvedIdentity = resolveWheelIdentity(loopResult.data.identity, currentState.identity, WheelType.INMOTION_V2)
                DecodeResult.Success(DecodedData(
                    telemetry = loopResult.data.telemetry,
                    identity = resolvedIdentity?.takeIf { it != currentState.identity },
                    bms = bmsSnapshot.takeIf { it != currentState.bms },
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
        val check = ProtocolChecksums.xorChecksum(buffer, offset = 2, length = buffer.size - 3)

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
    private fun processMessage(message: Message, currentState: DecoderState): FrameResult? {
        // Ensure model is detected from BLE name before any routing decisions.
        // Without this, early responses (e.g., settings) can arrive before model-specific
        // init responses, causing fallback parsers to misinterpret frame layouts.
        detectModelFromName(currentState.identity.btName)
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
            message.flags == Flag.EXTENDED -> {
                processExtendedMessage(message, currentState)
            }
            else -> null
        }
    }

    /**
     * Process P6 extended protocol messages.
     *
     * Extended responses have: cmd = sub_cmd from request, data[0] = 0x02,
     * data[1] = response type (0x80 + original param). Route based on response type.
     */
    private fun processExtendedMessage(message: Message, currentState: DecoderState): FrameResult? {
        val data = message.data
        if (data.size < 2) return null

        // BMS responses: command byte is battery ID (0x24-0x27)
        val cmd = message.command
        if (cmd in BmsId.BATTERY_1..BmsId.BATTERY_4) {
            val responseType = data[1].toInt() and 0xFF
            val bmsNum = cmd - BmsId.BATTERY_1 + 1
            return when (responseType) {
                BmsId.RESP_SERIAL -> processBmsSerial(bmsNum, data, currentState)
                BmsId.RESP_STATUS -> processBmsStatus(bmsNum, data, currentState)
                BmsId.RESP_VOLTAGES -> processBmsCellVoltages(bmsNum, data, currentState)
                else -> null
            }
        }

        // Standard P6 extended responses: data[0] = 0x02, data[1] = response type
        val responseType = data[1].toInt() and 0xFF
        return when (responseType) {
            0x86 -> processExtendedInit(data, currentState)
            0x87 -> processExtendedRealTime(data, currentState)
            0x90 -> null // ride statistics, not settings
            0x91 -> processExtendedTotalStats(data, currentState)
            0xA0 -> processExtendedSettings(data, currentState)
            else -> null
        }
    }

    /**
     * Process main info (car type, serial number, versions).
     */
    private fun processMainInfo(message: Message, currentState: DecoderState): FrameResult? {
        val data = message.data
        if (data.isEmpty()) return null

        var id = currentState.identity

        when (data[0].toInt() and 0xFF) {
            0x01 -> {
                // Car type: data format: [01, mainSeries, series, type, batch, feature, reverse]
                if (data.size >= 4) {
                    val series = data[2].toInt() and 0xFF
                    val type = data[3].toInt() and 0xFF
                    model = Model.findById(series, type)
                    isModelDetected = true
                    id = id.copy(
                        model = model.displayName,
                        wheelType = WheelType.INMOTION_V2
                    )
                }
            }
            0x02 -> {
                // Serial number
                if (data.size >= 17) {
                    serialNumber = data.copyOfRange(1, 17).decodeToString()
                    id = id.copy(serialNumber = serialNumber)
                }
            }
            0x06 -> {
                // Versions
                if (data.size >= 24) {
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
                    mainBoardVersion = mainBoard
                    id = id.copy(version = version)

                    // Set protocol version for V11
                    if (model == Model.V11) {
                        protoVer = if (mainBoard1 < 2 && mainBoard2 < 4) 1 else 2
                    }
                }
            }
        }

        // Propagate model/firmware to settings so buildCommand can read them from the state snapshot
        val settingsUpdate = if (isModelDetected || mainBoardVersion.isNotEmpty()) {
            val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
            im2.copy(modelId = model.id, mainBoardVersion = mainBoardVersion)
        } else null

        return FrameResult(identity = id, settings = settingsUpdate, hasNewData = false, frameType = "MAIN_INFO")
    }

    // ==================== P6 Extended Protocol ====================

    /**
     * Process extended init response (0x86).
     * Contains serial number at data[5:21] and car type at data[27:29].
     */
    private fun processExtendedInit(data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 30) return null
        var id = currentState.identity

        // Serial number at data[5..20] (16 ASCII chars)
        if (data.size >= 21) {
            serialNumber = data.copyOfRange(5, 21).decodeToString().trimEnd('\u0000')
            id = id.copy(serialNumber = serialNumber)
        }

        // Car type: series at data[27], type at data[28]
        val series = data[27].toInt() and 0xFF
        val type = data[28].toInt() and 0xFF
        model = Model.findById(series, type)
        isModelDetected = true
        id = id.copy(
            model = model.displayName,
            wheelType = WheelType.INMOTION_V2
        )

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        val settingsUpdate = im2.copy(modelId = model.id, mainBoardVersion = mainBoardVersion)

        return FrameResult(identity = id, settings = settingsUpdate, hasNewData = false, frameType = "EXT_INIT")
    }

    /**
     * Process extended real-time telemetry (0x87).
     * data[0:4] = sub-header [02 87 01 00], data[4:] = 96-byte telemetry payload.
     *
     * 0x87 is a lightweight keep-alive response — it contains core telemetry
     * (voltage, current, speed, torque, battery, power, angles, mileage) but
     * NOT temperatures, speed/current limits, mode, or error bytes. Those
     * offsets contain unrelated data, so we must NOT delegate to parseByLayout.
     */
    private fun processExtendedRealTime(data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 4) return null
        val payload = data.copyOfRange(4, data.size)
        if (payload.size < 30) return null

        val voltage = ByteUtils.shortFromBytesLE(payload, 0)
        val current = ByteUtils.signedShortFromBytesLE(payload, 2)
        val speed = ByteUtils.signedShortFromBytesLE(payload, 8)
        val torque = ByteUtils.signedShortFromBytesLE(payload, 12)
        val discharge = ByteUtils.signedShortFromBytesLE(payload, 14)
        val batLevel = (100 - kotlin.math.abs(discharge) / 100.0).roundToInt().coerceIn(0, 100)
        val batPower = ByteUtils.signedShortFromBytesLE(payload, 16)
        val motPower = ByteUtils.signedShortFromBytesLE(payload, 18)
        val pitchAngle = ByteUtils.signedShortFromBytesLE(payload, 20)
        val rollAngle = ByteUtils.signedShortFromBytesLE(payload, 22)
        val mileage = (ByteUtils.shortFromBytesLE(payload, 28) * 10).toLong()

        hasReceivedTelemetry = true
        return FrameResult(
            telemetry = currentState.telemetry.copy(
                voltage = voltage,
                current = current,
                speed = speed,
                torque = torque / 100.0,
                motorPower = motPower.toDouble(),
                power = batPower * 100,
                batteryLevel = batLevel,
                angle = pitchAngle / 100.0,
                roll = rollAngle / 100.0,
                wheelDistance = mileage
            ),
            hasNewData = true,
            frameType = "EXT_REAL_TIME"
        )
    }

    /**
     * Process extended settings response (0xA0 = SETTINGS | 0x80).
     * Strip the 2-byte extended prefix (02 A0) and delegate to the model-specific parser.
     */
    private fun processExtendedSettings(data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 4) return null
        // Strip 2-byte extended prefix to get standard settings payload
        val payload = data.copyOfRange(2, data.size)
        return processSettings(Message(Flag.EXTENDED, payload.size, 0, payload), currentState)
    }

    /**
     * Process extended total stats response (0x91).
     * data[2:6] contains total distance (LE int × 10 for meters).
     */
    private fun processExtendedTotalStats(data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 6) return null
        val totalDistance = ByteUtils.intFromBytesLE(data, 2).toLong() * 10
        return FrameResult(
            telemetry = currentState.telemetry.copy(totalDistance = totalDistance),
            hasNewData = false,
            frameType = "EXT_TOTAL_STATS"
        )
    }

    /**
     * Process settings data.
     */
    private fun processSettings(message: Message, currentState: DecoderState): FrameResult? {
        return when (model) {
            Model.V11 -> parseSettingsV11(message.data, currentState)
            Model.V11Y -> parseSettingsExtended(message.data, currentState)
            Model.V12HS, Model.V12HT, Model.V12PRO -> parseSettingsV12(message.data, currentState)
            Model.V13, Model.V13PRO -> parseSettingsV13(message.data, currentState)
            Model.V14g, Model.V14s -> parseSettingsV14(message.data, currentState)
            Model.P6 -> parseSettingsP6(message.data, currentState)
            Model.V9, Model.V12S -> parseSettingsExtended(message.data, currentState)
            Model.UNKNOWN -> null
        }
    }

    /**
     * Process diagnostic data.
     */
    private fun processDiagnostic(message: Message, currentState: DecoderState): FrameResult? {
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
     * Process battery real-time info (flag 0x14, command 0x05).
     * General battery summary — not per-cell BMS data.
     */
    private fun processBatteryRealTimeInfo(message: Message, currentState: DecoderState): FrameResult? {
        return null // General battery info; per-cell BMS uses extended protocol
    }

    // ==================== BMS Parsing (Extended Protocol) ====================

    private fun bmsForNum(num: Int): SmartBms = if (num <= 1) bms1 else bms2

    /**
     * Process BMS serial/init response (sub-type 0x04, response 0x84).
     * Initializes the BMS instance and extracts serial number.
     *
     * EUC World offsets (relative to bArr2 which includes command byte):
     * bytes 23-42 = 20-char ASCII serial. In our data array (no command byte),
     * that's data[22..41].
     */
    private fun processBmsSerial(bmsNum: Int, data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 42) return null
        val bms = bmsForNum(bmsNum)
        bms.cellNum = model.cellCount

        // Extract serial number (20 ASCII chars at EUC World offset 23 = data[22])
        val serialBytes = data.copyOfRange(22, minOf(42, data.size))
        bms.serialNumber = serialBytes
            .map { it.toInt().toChar() }
            .joinToString("")
            .trim('\u0000', ' ')

        bmsInitDone = true
        return FrameResult(
            hasNewData = false,
            frameType = "BMS${bmsNum}_SERIAL"
        )
    }

    /**
     * Process BMS status response (sub-type 0x01, response 0x81).
     * Contains voltage, current, SOC, and temperatures.
     *
     * EUC World offsets (bArr2-relative, subtract 1 for data index):
     * [9]  = short LE × 0.01 → voltage
     * [11] = short LE × 0.01 → current
     * [13] = short LE × 0.01 → (third metric)
     * [19] = unsigned short → SOC %
     */
    private fun processBmsStatus(bmsNum: Int, data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 19) return null
        val bms = bmsForNum(bmsNum)

        // Offsets: EUC World bArr2[N] = data[N-1]
        bms.voltage = ByteUtils.signedShortFromBytesLE(data, 8) * 0.01
        if (data.size > 11) {
            bms.current = ByteUtils.signedShortFromBytesLE(data, 10) * 0.01
        }
        if (data.size > 19) {
            bms.remPerc = ByteUtils.shortFromBytesLE(data, 18)
        }
        // Temperatures at EUC World offsets 27, 28 = data[26], data[27]
        if (data.size > 27) {
            bms.temp1 = (data[26].toInt() and 0xFF).toDouble()
        }
        if (data.size > 28) {
            bms.temp2 = (data[27].toInt() and 0xFF).toDouble()
        }

        return FrameResult(
            hasNewData = false,
            frameType = "BMS${bmsNum}_STATUS"
        )
    }

    /**
     * Process BMS cell voltages response (sub-type 0x02, response 0x82).
     * Cell i = LE short at EUC World offset (i*2 + 3) = data[(i*2) + 2], in millivolts.
     */
    private fun processBmsCellVoltages(bmsNum: Int, data: ByteArray, currentState: DecoderState): FrameResult? {
        val bms = bmsForNum(bmsNum)
        val cellCount = model.cellCount.coerceAtMost(SmartBms.MAX_CELLS)
        bms.cellNum = cellCount

        for (i in 0 until cellCount) {
            val offset = (i * 2) + 2  // EUC World: (i*2) + 3 in bArr2 = (i*2) + 2 in data
            if (offset + 1 >= data.size) break
            val mv = ByteUtils.signedShortFromBytesLE(data, offset)
            bms.cells[i] = mv * 0.001
        }

        updateBmsCellStats(bms, cellCount)

        return FrameResult(
            hasNewData = false,
            frameType = "BMS${bmsNum}_VOLTAGES"
        )
    }

    private fun updateBmsCellStats(bms: SmartBms, cellCount: Int) {
        if (cellCount == 0) return
        bms.minCell = bms.cells[0]
        bms.maxCell = bms.cells[0]
        bms.maxCellNum = 1
        bms.minCellNum = 1
        var totalVolt = 0.0

        for (i in 0 until cellCount) {
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
        bms.avgCell = if (cellCount > 0) totalVolt / cellCount else 0.0
    }

    /**
     * Process total statistics.
     */
    private fun processTotalStats(message: Message, currentState: DecoderState): FrameResult? {
        val data = message.data
        if (data.size < 20) return null

        val totalDistance = ByteUtils.intFromBytesLE(data, 0).toLong() * 10

        return FrameResult(
            telemetry = currentState.telemetry.copy(totalDistance = totalDistance),
            hasNewData = false,
            frameType = "TOTAL_STATS"
        )
    }

    /**
     * Fallback model detection from BLE device name when protocol-based detection fails.
     * Some models (e.g., P6) may not respond to standard/extended init car-type requests,
     * but the BLE device name always contains the model prefix.
     */
    private fun detectModelFromName(btName: String) {
        if (model != Model.UNKNOWN || btName.isEmpty()) return
        val name = btName.uppercase()
        val detected = when {
            name.startsWith("V11Y") -> Model.V11Y
            name.startsWith("V11") -> Model.V11
            name.startsWith("V12S") -> Model.V12S
            name.startsWith("V12HS") -> Model.V12HS
            name.startsWith("V12HT") -> Model.V12HT
            name.startsWith("V12PRO") || name.startsWith("V12 PRO") -> Model.V12PRO
            name.startsWith("V13PRO") || name.startsWith("V13 PRO") -> Model.V13PRO
            name.startsWith("V13") -> Model.V13
            name.startsWith("V14") && name.contains("50S") -> Model.V14s
            name.startsWith("V14") -> Model.V14g
            name.startsWith("V9") -> Model.V9
            name.startsWith("P6") -> Model.P6
            else -> null
        }
        if (detected != null) {
            model = detected
            isModelDetected = true
        }
    }

    /**
     * Process real-time telemetry info.
     */
    private fun processRealTimeInfo(message: Message, currentState: DecoderState): FrameResult? {
        val result = when (model) {
            Model.V11 -> {
                if (protoVer < 2) parseRealTimeInfoV11Old(message.data, currentState)
                else parseByLayout(Layouts.V11_1_4, message.data, currentState)
            }
            Model.V11Y, Model.V9, Model.V12S -> parseByLayout(Layouts.EXTENDED, message.data, currentState)
            Model.P6 -> parseByLayout(Layouts.P6, message.data, currentState)
            Model.V12HS, Model.V12HT, Model.V12PRO -> parseByLayout(Layouts.V12, message.data, currentState)
            Model.V13, Model.V13PRO -> parseByLayout(Layouts.V13, message.data, currentState)
            Model.V14g, Model.V14s -> parseByLayout(Layouts.V14, message.data, currentState)
            Model.UNKNOWN -> null
        }
        if (result?.hasNewData == true) {
            hasReceivedTelemetry = true
        }
        return result
    }

    // ==================== Layout-driven Real-Time Parsing ====================

    private enum class MileageType { SHORT_TIMES_10, INT_REV_LE }
    private enum class BatLevelType { SINGLE_BYTE_MASKED, SHORT_DIV_100, DUAL_SHORT_AVG, INVERTED_SHORT_DIV_100 }

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
        /**
         * P6 layout — shares most offsets with EXTENDED/V14 but battery level
         * is at offset 14 using an inverted formula: the wheel reports "discharge
         * percentage" (how much has been used × 100), so remaining % = 100 - abs(value)/100.
         * Confirmed via EUC World decompilation (m8820V parser).
         */
        val P6 = RealTimeLayout(
            minSize = 78, voltage = 0, current = 2, speed = 8, torque = 12, pwm = 34,
            batPower = 16, motPower = 18, pitchAngle = 20, rollAngle = 22,
            mileage = 28, batLevel = 14, speedLimit = 40, currentLimit = 50,
            mosTemp = 58, temp2 = 59, cpuTemp = 62, imuTemp = 63,
            modeString = 74, error = 77,
            mileageType = MileageType.SHORT_TIMES_10,
            batLevelType = BatLevelType.INVERTED_SHORT_DIV_100,
            liftedByteOffset = 1
        )
    }

    /**
     * Shared parser that reads telemetry fields according to a [RealTimeLayout].
     */
    private fun parseByLayout(layout: RealTimeLayout, data: ByteArray, currentState: DecoderState): FrameResult? {
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
            BatLevelType.INVERTED_SHORT_DIV_100 -> {
                // P6: wheel reports "discharge %" (× 100). Remaining = 100 - abs(value)/100.
                val discharge = ByteUtils.signedShortFromBytesLE(data, layout.batLevel)
                (100 - kotlin.math.abs(discharge) / 100.0).roundToInt().coerceIn(0, 100)
            }
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
            telemetry = currentState.telemetry.copy(
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
                alert = alert
            ),
            identity = currentState.identity.copy(
                modeStr = modeStr,
                model = model.displayName,
                wheelType = WheelType.INMOTION_V2
            ),
            hasNewData = true,
            news = alert.ifEmpty { null },
            frameType = "REAL_TIME_INFO"
        )
    }

    // ==================== V11 Old Protocol Parsing ====================

    /**
     * V11 old protocol (protoVer < 2): unique field order and conditional state offset.
     * Not suitable for layout-driven parsing due to genuinely different structure.
     */
    private fun parseRealTimeInfoV11Old(data: ByteArray, currentState: DecoderState): FrameResult? {
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
            telemetry = currentState.telemetry.copy(
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
                alert = alert
            ),
            identity = currentState.identity.copy(
                modeStr = modeStr,
                model = model.displayName,
                wheelType = WheelType.INMOTION_V2
            ),
            hasNewData = true,
            news = alert.ifEmpty { null },
            frameType = "REAL_TIME_INFO"
        )
    }

    // ==================== Settings Parsing ====================

    private fun parseSettingsV11(data: ByteArray, currentState: DecoderState): FrameResult? {
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

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        return FrameResult(
            settings = im2.copy(
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
            hasNewData = false,
            frameType = "SETTINGS"
        )
    }

    private fun parseSettingsV12(data: ByteArray, currentState: DecoderState): FrameResult? {
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

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        return FrameResult(
            settings = im2.copy(
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
            hasNewData = false,
            frameType = "SETTINGS"
        )
    }

    /**
     * Parse settings for V13/V14 layout (shared).
     */
    private fun parseSettingsV13V14(data: ByteArray, currentState: DecoderState): FrameResult? {
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

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        return FrameResult(
            settings = im2.copy(
                maxSpeed = speedLim,
                pedalTilt = pedalTilt,
                pedalSensitivity = sensitivity,
                rideMode = offroad,
                fancierMode = fancier,
                mute = muteFlag,
                drl = drlFlag,
                transportMode = transpMode
            ),
            hasNewData = false,
            frameType = "SETTINGS"
        )
    }

    private fun parseSettingsV13(data: ByteArray, currentState: DecoderState): FrameResult? {
        return parseSettingsV13V14(data, currentState)
    }

    private fun parseSettingsV14(data: ByteArray, currentState: DecoderState): FrameResult? {
        return parseSettingsV13V14(data, currentState)
    }

    /**
     * Parse settings for V11Y/V9/V12S layout (shared).
     * Same as V13/V14 plus handleButton, goHome.
     */
    private fun parseSettingsExtended(data: ByteArray, currentState: DecoderState): FrameResult? {
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

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        return FrameResult(
            settings = im2.copy(
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
            hasNewData = false,
            frameType = "SETTINGS"
        )
    }

    /**
     * Parse P6 settings response.
     *
     * Layout confirmed via BLE captures with known setting values:
     * - data[0]: sub-type echo (0x20)
     * - data[9:10]: tilt-back speed (LE, km/h × 100)
     * - data[11:12]: speed alarm (LE, km/h × 100)
     * - data[15:16]: PWM tilt-back limit (LE, × 100)
     * - data[17:18]: PWM level 1 alarm (LE, × 100)
     * - data[19:20]: PWM level 2 alarm (LE, × 100)
     * - data[21:22]: balance angle (LE, degrees × 100)
     * - data[25]: ride mode flags (bit 4 = fancier/sport)
     * - data[26]: comfort sensitivity (0-100%)
     * - data[27]: classic sensitivity (0-100%)
     * - data[30]: standby time (minutes)
     * - data[32]: charging limit (%)
     * - data[41]: logo light brightness (0-100%)
     * - data[42]: cutout angle / tilt angle limit (degrees)
     * - data[46]: flags A (bit 3 = auto headlight)
     * - data[48]: flags B (bit 5 = DRL)
     */
    private fun parseSettingsP6(data: ByteArray, currentState: DecoderState): FrameResult? {
        if (data.size < 50) return null

        val maxSpd = ByteUtils.shortFromBytesLE(data, 9) / 100
        val speedAlarm = ByteUtils.shortFromBytesLE(data, 11) / 100
        val pwmTiltBack = ByteUtils.shortFromBytesLE(data, 15) / 100
        val pwmAlarm1 = ByteUtils.shortFromBytesLE(data, 17) / 100
        val pwmAlarm2 = ByteUtils.shortFromBytesLE(data, 19) / 100
        val balAngle = ByteUtils.signedShortFromBytesLE(data, 21)
        val comfSens = data[26].toInt() and 0xFF
        val classSens = data[27].toInt() and 0xFF
        val fancier = ((data[25].toInt() shr 4) and 0x01) != 0
        val sensitivity = if (fancier) classSens else comfSens
        val standbyMinutes = data[30].toInt() and 0xFF
        val chargingLimit = data[32].toInt() and 0xFF
        val minTirePressure = ByteUtils.shortFromBytesLE(data, 39)
        val logoBrightness = data[41].toInt() and 0xFF
        val cutout = data[42].toInt() and 0xFF
        val chargingAC220V = data[44].toInt() and 0xFF
        val chargingAC110V = data[45].toInt() and 0xFF

        // Flag byte 46: bit 0 = beep (inverted for mute), bit 3 = auto headlight, bit 6 = transport mode
        val flags46 = data[46].toInt() and 0xFF
        val beepEnabled = (flags46 and 0x01) != 0
        val autoHeadlight = ((flags46 shr 3) and 0x01) != 0
        val transportMode = ((flags46 shr 6) and 0x01) != 0

        // Flag byte 47: bit 5 = auto screen off
        val flags47 = data[47].toInt() and 0xFF
        val autoScreenOff = ((flags47 shr 5) and 0x01) != 0

        // Flag byte 48: bit 3 = RideConnect low battery, bit 4 = RideConnect switch,
        //               bit 5 = DRL, bit 6 = ignore tire pressure
        val flags48 = data[48].toInt() and 0xFF
        val rideConnectLowBattery = ((flags48 shr 3) and 0x01) != 0
        val rideConnectSwitch = ((flags48 shr 4) and 0x01) != 0
        val drlFlag = ((flags48 shr 5) and 0x01) != 0
        val ignoreTirePressure = ((flags48 shr 6) and 0x01) != 0

        // Flag byte 49: bit 3 = auto lock
        val flags49 = data[49].toInt() and 0xFF
        val autoLock = ((flags49 shr 3) and 0x01) != 0

        val im2 = currentState.settings as? WheelSettings.InMotionV2 ?: WheelSettings.InMotionV2()
        return FrameResult(
            settings = im2.copy(
                maxSpeed = maxSpd,
                pedalSensitivity = sensitivity,
                fancierMode = fancier,
                mute = !beepEnabled,
                autoHeadlight = autoHeadlight,
                transportMode = transportMode,
                logoLightBrightness = logoBrightness,
                cutoutAngle = cutout,
                drl = drlFlag,
                speedAlarm = speedAlarm,
                pwmTiltBackLimit = pwmTiltBack,
                pwmAlarm1 = pwmAlarm1,
                pwmAlarm2 = pwmAlarm2,
                balanceAngle = balAngle,
                standbyTime = standbyMinutes,
                chargeLimit = chargingLimit,
                autoScreenOff = autoScreenOff,
                autoLock = autoLock,
                ignoreTirePressure = ignoreTirePressure,
                rideConnectSwitch = rideConnectSwitch,
                rideConnectLowBattery = rideConnectLowBattery,
                minTirePressure = minTirePressure,
                chargingCurrentAC110V = chargingAC110V,
                chargingCurrentAC220V = chargingAC220V
            ),
            hasNewData = false,
            frameType = "SETTINGS"
        )
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

    override fun isReady(): Boolean =
        hasReceivedTelemetry || (isModelDetected && version.isNotEmpty())

    override fun getUnpackerStats(): UnpackerStats = unpacker.stats

    override fun reset() {
        unpacker.reset()
        model = Model.UNKNOWN
        protoVer = 0
        serialNumber = ""
        version = ""
        mainBoardVersion = ""
        isModelDetected = false
        hasReceivedTelemetry = false
        keepAliveCounter = 0
        bms1 = SmartBms()
        bms2 = SmartBms()
        bmsInitDone = false
        bmsPollCounter = 0
    }

    override fun getCapabilities(): CapabilitySet {
        val commands = buildMap {
            putAll(BASE_COMMANDS)
            when (model) {
                Model.V11, Model.V11Y -> putAll(V11_COMMANDS)
                Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S -> putAll(V12_COMMANDS)
                Model.V13, Model.V13PRO -> putAll(V13_V14_COMMANDS)
                Model.V14g, Model.V14s -> {
                    putAll(V13_V14_COMMANDS)
                    putAll(V14_COMMANDS)
                }
                Model.P6 -> {
                    // P6 has no manual headlight toggle or brightness (auto-only headlight)
                    remove(SettingsCommandId.LIGHT_MODE)
                    remove(SettingsCommandId.LIGHT_BRIGHTNESS)
                    // P6 has no pedal tilt setting or speaker volume
                    remove(SettingsCommandId.PEDAL_TILT)
                    remove(SettingsCommandId.SPEAKER_VOLUME)
                    putAll(P6_COMMANDS)
                }
                Model.V9 -> { /* base commands only */ }
                Model.UNKNOWN -> return CapabilitySet()
            }
        }
        return commands.resolveAt(
            firmwareLevel = protoVer,
            detectedModel = model.displayName,
            firmwareVersion = version
        )
    }

    // Model grouping helpers (used by event-loop methods)
    private val isV9Like get() = model == Model.V9 || model == Model.P6
    private val isV11Family get() = model == Model.V11 || model == Model.V11Y
    private val isV12Family get() = model == Model.V12HS || model == Model.V12HT || model == Model.V12PRO || model == Model.V12S
    private val isV13Family get() = model == Model.V13 || model == Model.V13PRO
    private val isV14Family get() = model == Model.V14g || model == Model.V14s
    private val isV9OrV12 get() = isV9Like || isV12Family

    /**
     * Check if main board firmware version is at least major.minor.
     * Parses mainBoardVersion string like "1.4.123".
     */
    private fun isFirmwareAtLeast(major: Int, minor: Int): Boolean =
        isFirmwareAtLeast(mainBoardVersion, major, minor)

    private fun controlMsg(vararg bytes: Byte): ByteArray =
        buildMessage(Flag.DEFAULT, Command.CONTROL, bytes.toList().toByteArray())

    override fun buildCommand(command: WheelCommand, state: DecoderState?): List<WheelCommand> {
        val im2 = state?.settings as? WheelSettings.InMotionV2
        val cmdModel = Model.findByFullId(im2?.modelId ?: 0)
        val cmdFwVersion = im2?.mainBoardVersion ?: ""
        val msg = buildCommandMessage(command, cmdModel, cmdFwVersion) ?: return emptyList()
        return listOf(WheelCommand.SendBytes(msg))
    }

    /**
     * Build the raw message bytes for a command, or null if unsupported for the current model.
     * Model-dependent routing based on EUC World reverse-engineering.
     */
    private fun buildCommandMessage(command: WheelCommand, model: Model, firmware: String): ByteArray? {
        val isV11Family = model == Model.V11 || model == Model.V11Y
        val isV12Family = model == Model.V12HS || model == Model.V12HT || model == Model.V12PRO || model == Model.V12S
        val isV13Family = model == Model.V13 || model == Model.V13PRO
        val isV14Family = model == Model.V14g || model == Model.V14s
        val isV9OrV12 = model == Model.V9 || model == Model.P6 || isV12Family
        return when (command) {
            is WheelCommand.Beep -> playBeepMessage(0x18)

            is WheelCommand.SetLight -> buildLightMessage(command.enabled, model, firmware)

            is WheelCommand.SetLock ->
                controlMsg(0x31, boolByte(command.locked))

            is WheelCommand.PowerOff ->
                buildMessage(Flag.INITIAL, Command.DIAGNOSTIC, byteArrayOf(0x81.toByte(), 0x00))

            is WheelCommand.Calibrate ->
                controlMsg(0x42, 0x01, 0x00, 0x01)

            is WheelCommand.SetHandleButton ->
                // Inverted: enabled=true sends 0 (button disabled), false sends 1
                controlMsg(0x2E, boolByte(!command.enabled))

            is WheelCommand.SetRideMode ->
                controlMsg(0x23, boolByte(command.enabled))

            is WheelCommand.SetSpeakerVolume ->
                controlMsg(0x26, (command.volume.coerceIn(0, 100) and 0xFF).toByte())

            is WheelCommand.SetPedalTilt -> {
                // angle is in 1/10 degree (internal units), wire format is degrees × 100
                val value = (command.angle * 10).toShort()
                controlMsg(0x22, leShortLo(value), leShortHi(value))
            }

            is WheelCommand.SetPedalSensitivity -> {
                val s = (command.sensitivity.coerceIn(0, 100) and 0xFF).toByte()
                when (model) {
                    Model.V9, Model.P6 -> controlMsg(0x25, 0x64, s)
                    Model.V11, Model.V11Y, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S,
                    Model.V13, Model.V13PRO, Model.V14g, Model.V14s -> controlMsg(0x25, s, 0x64)
                    Model.UNKNOWN -> null
                }
            }

            is WheelCommand.SetMaxSpeed -> {
                val value = (command.speed * 100).toShort()
                val lo = leShortLo(value)
                val hi = leShortHi(value)
                when (model) {
                    Model.V14g, Model.V14s -> buildMessage(Flag.EXTENDED, Command.MAIN_INFO,
                        byteArrayOf(0x21, 0x60, 0x21, lo, hi, 0x00, 0x00))
                    Model.V11, Model.V11Y, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S,
                    Model.V13, Model.V13PRO, Model.V9, Model.P6 -> controlMsg(0x21, lo, hi)
                    Model.UNKNOWN -> null
                }
            }

            is WheelCommand.SetTransportMode ->
                controlMsg(0x32, boolByte(command.enabled))

            is WheelCommand.SetDrl -> {
                val subCmd: Byte = when (model) {
                    Model.P6 -> 0x4e  // P6 DRL (logo light) toggle
                    Model.V9 -> 0x44  // V9 DRL
                    Model.V11, Model.V11Y, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S,
                    Model.V13, Model.V13PRO, Model.V14g, Model.V14s -> 0x2D
                    Model.UNKNOWN -> return null
                }
                controlMsg(subCmd, boolByte(command.enabled))
            }

            is WheelCommand.SetGoHomeMode ->
                controlMsg(0x37, boolByte(command.enabled))

            is WheelCommand.SetFancierMode ->
                controlMsg(0x24, boolByte(command.enabled))

            is WheelCommand.SetPerformanceMode ->
                controlMsg(0x24, boolByte(command.enabled))

            is WheelCommand.SetMute ->
                // Inverted: mute=true sends 0, mute=false sends 1
                controlMsg(0x2C, boolByte(!command.enabled))

            is WheelCommand.SetFanQuiet -> {
                // V11/V11Y only; shares sub-cmd 0x38 with MotorSoundSensitivity on V12
                if (!isV11Family) return null
                controlMsg(0x38, boolByte(command.enabled))
            }

            is WheelCommand.SetFan -> {
                // V11/V11Y only, firmware-dependent sub-command
                if (!isV11Family) return null
                val subCmd: Byte = if (isFirmwareAtLeast(firmware, 1, 4)) 0x53 else 0x43
                controlMsg(subCmd, boolByte(command.enabled))
            }

            is WheelCommand.SetLightBrightness ->
                controlMsg(0x2B, (command.value and 0xFF).toByte())

            is WheelCommand.SetAutoHeadlight -> {
                if (!isV12Family && !isV13Family && !isV14Family && model != Model.P6) return null
                controlMsg(0x2F, boolByte(command.enabled))
            }

            is WheelCommand.SetMotorNoLoadDetection ->
                controlMsg(0x36, boolByte(command.enabled))

            is WheelCommand.SetLowBatteryRiding ->
                controlMsg(0x37, boolByte(command.enabled))

            is WheelCommand.SetMotorSound ->
                controlMsg(0x39, boolByte(command.enabled))

            is WheelCommand.SetMotorSoundSensitivity -> {
                // V12 only; shares sub-cmd 0x38 with FanQuiet on V11
                if (!isV12Family) return null
                controlMsg(0x38, (command.sensitivity.coerceIn(0, 100) and 0xFF).toByte())
            }

            is WheelCommand.SetScreenAutoOff -> {
                if (!isV12Family && model != Model.P6) return null
                controlMsg(0x3D, boolByte(command.enabled))
            }

            is WheelCommand.SetStandbyTime -> {
                val value = command.minutes.toShort()
                controlMsg(0x28, leShortLo(value), leShortHi(value))
            }

            is WheelCommand.SetExtendedLateralTilt ->
                controlMsg(0x45, boolByte(command.enabled))

            is WheelCommand.SetSpeedAlarms -> {
                // V9/V12 only
                if (!isV9OrV12) return null
                val a1 = (command.alarm1 * 100).toShort()
                val a2 = (command.alarm2 * 100).toShort()
                controlMsg(0x3E, leShortLo(a1), leShortHi(a1), leShortLo(a2), leShortHi(a2))
            }

            is WheelCommand.SetSplitRidingModes -> {
                val subCmd: Byte = when (model) {
                    Model.V9, Model.P6, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S -> 0x42
                    Model.V11, Model.V11Y, Model.V13, Model.V13PRO, Model.V14g, Model.V14s -> 0x3E
                    Model.UNKNOWN -> return null
                }
                controlMsg(subCmd, boolByte(command.enabled))
            }

            is WheelCommand.SetSplitRidingModesSettings -> {
                val subCmd: Byte = when (model) {
                    Model.V9, Model.P6, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S -> 0x40
                    Model.V11, Model.V11Y, Model.V13, Model.V13PRO, Model.V14g, Model.V14s -> 0x3F
                    Model.UNKNOWN -> return null
                }
                val accel = (command.acceleration.coerceIn(0, 100) and 0xFF).toByte()
                val braking = (command.braking.coerceIn(0, 100) and 0xFF).toByte()
                controlMsg(subCmd, accel, braking)
            }

            // Commands not found in EUC World — return null (unsupported or need BLE capture)
            is WheelCommand.SetBermAngleMode -> {
                // V13/V14 only — confirmed: V13 factory bermAngleModeCmd = 0x45
                if (!isV13Family && !isV14Family) return null
                controlMsg(0x45, boolByte(command.enabled))
            }
            is WheelCommand.SetBermAngle -> null
            is WheelCommand.SetTurningSensitivity -> null
            is WheelCommand.SetOnePedalMode -> null
            is WheelCommand.SetSpeedingBrakingMode -> null
            is WheelCommand.SetSpeedingBrakingAngle -> null
            is WheelCommand.SetSoundWave -> null
            is WheelCommand.SetSoundWaveSensitivity -> null
            is WheelCommand.SetSafeSpeedLimit -> {
                // V13/V14 only — confirmed: V13 factory safeSpeedLimitCmd = 0x44
                if (!isV13Family && !isV14Family) return null
                controlMsg(0x44, boolByte(command.enabled))
            }
            is WheelCommand.SetBackwardOverspeedAlert -> null
            is WheelCommand.SetTailLightMode -> {
                if (model != Model.P6) return null
                controlMsg(0x3b, (command.mode and 0xFF).toByte())
            }
            is WheelCommand.SetTurnSignalMode -> {
                if (model != Model.P6) return null
                controlMsg(0x30, (command.mode and 0xFF).toByte())
            }
            is WheelCommand.SetLogoLightBrightness -> {
                if (model != Model.P6) return null
                controlMsg(0x44, (command.brightness.coerceIn(0, 100) and 0xFF).toByte())
            }
            is WheelCommand.SetLightEffect -> null
            is WheelCommand.SetLightEffectMode -> {
                // V13/V14 only — confirmed: V13 factory lightEffectModeCmd = 0x2D
                // Shares sub-cmd 0x2D with DRL; DRL sends bool, this sends mode int
                if (!isV13Family && !isV14Family) return null
                controlMsg(0x2D, (command.mode and 0xFF).toByte())
            }
            is WheelCommand.SetTwoBatteryMode -> {
                // V14 only — confirmed: V14 factory genSetTwoBatteryModeMsg cmd 0x48
                if (!isV14Family) return null
                controlMsg(0x48, boolByte(command.enabled))
            }
            is WheelCommand.SetLowBatterySafeMode -> null
            is WheelCommand.SetSpinKill -> null
            is WheelCommand.SetCruise -> null
            is WheelCommand.SetLoadDetect -> null
            is WheelCommand.SetChargeLimit ->
                controlMsg(0x28, (command.percentage.coerceIn(0, 100) and 0xFF).toByte())

            // P6-specific commands
            is WheelCommand.SetAutoLock -> {
                if (model != Model.P6) return null
                controlMsg(0x2D, boolByte(command.enabled))
            }
            is WheelCommand.SetBalanceAngle -> {
                if (model != Model.P6) return null
                val value = command.angle.toShort()
                controlMsg(0x22, leShortLo(value), leShortHi(value))
            }
            is WheelCommand.SetChargingCurrent -> {
                if (model != Model.P6) return null
                controlMsg(0x39, (command.ac220v and 0xFF).toByte(), (command.ac110v and 0xFF).toByte())
            }
            is WheelCommand.SetIgnoreTirePressure -> {
                if (model != Model.P6) return null
                controlMsg(0x4F, boolByte(command.enabled))
            }
            is WheelCommand.SetMinTirePressure -> {
                if (model != Model.P6) return null
                controlMsg(0x4D, (command.value1 and 0xFF).toByte(), (command.value2 and 0xFF).toByte())
            }
            is WheelCommand.SetRideConnectSwitch -> {
                if (model != Model.P6) return null
                controlMsg(0x48, boolByte(command.enabled))
            }
            is WheelCommand.SetRideConnectLowBattery -> {
                if (model != Model.P6) return null
                controlMsg(0x47, boolByte(command.enabled))
            }
            is WheelCommand.SetSpeedTiltbackEnable -> {
                if (model != Model.P6) return null
                controlMsg(0x3E, boolByte(command.enabled))
            }

            else -> null
        }
    }

    /**
     * Build headlight on/off message. Model and firmware dependent.
     */
    private fun buildLightMessage(on: Boolean, model: Model, firmware: String): ByteArray? {
        val enable = boolByte(on)
        return when (model) {
            Model.P6 -> null // P6 has no manual headlight toggle (auto-only)
            Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S -> controlMsg(0x50, enable, 0x00)
            Model.V9 -> controlMsg(0x50, enable, enable)
            Model.V11, Model.V11Y -> {
                if (!isFirmwareAtLeast(firmware, 1, 4)) controlMsg(0x40, enable)
                else controlMsg(0x50, enable)
            }
            Model.V13, Model.V13PRO, Model.V14g, Model.V14s -> controlMsg(0x50, enable)
            Model.UNKNOWN -> null
        }
    }

    private fun boolByte(value: Boolean): Byte = if (value) 1 else 0
    private fun leShortLo(value: Short): Byte = (value.toInt() and 0xFF).toByte()
    private fun leShortHi(value: Short): Byte = ((value.toInt() shr 8) and 0xFF).toByte()

    override fun getInitCommands(): List<WheelCommand> {
        val commands = mutableListOf(
            // Standard IM2 init (V11/V12/V13/V14)
            WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01))),
            WheelCommand.SendDelayed(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02)), 100),
            WheelCommand.SendDelayed(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06)), 200),
            WheelCommand.SendDelayed(buildMessage(Flag.DEFAULT, Command.SETTINGS, byteArrayOf(0x20)), 300),
            WheelCommand.SendDelayed(buildMessage(Flag.DEFAULT, Command.TOTAL_STATS, byteArrayOf()), 400),
            // P6 extended init: combined car type + serial response
            WheelCommand.SendDelayed(
                buildMessage(Flag.EXTENDED, Command.MAIN_INFO, byteArrayOf(0x21, 0x06)), 500
            ),
            // P6 extended settings request
            WheelCommand.SendDelayed(
                buildMessage(Flag.EXTENDED, Command.MAIN_INFO, byteArrayOf(0x21, 0x20, 0x20)), 600
            ),
            // P6 total stats
            WheelCommand.SendDelayed(
                buildMessage(Flag.EXTENDED, Command.MAIN_INFO, byteArrayOf(0x21, 0x11)), 700
            ),
            // BMS serial init for battery 1
            WheelCommand.SendDelayed(buildBmsRequest(BmsId.BATTERY_1, 0x04), 800)
        )
        // BMS serial init for battery 2 (V13/V14)
        commands.add(WheelCommand.SendDelayed(buildBmsRequest(BmsId.BATTERY_2, 0x04), 900))
        return commands
    }

    override fun getKeepAliveCommand(): WheelCommand {
        keepAliveCounter++
        val needsInit = !isModelDetected || serialNumber.isEmpty() || version.isEmpty()
        return if (needsInit && keepAliveCounter % 4 == 0) {
            getNextInitRetryCommand()
        } else if (isModelDetected && keepAliveCounter % 4 == 3) {
            // Every 4th tick (~1s): send next BMS poll request
            getNextBmsPollCommand()
        } else {
            when (model) {
                Model.P6 -> WheelCommand.SendBytes(
                    buildMessage(Flag.EXTENDED, Command.MAIN_INFO, byteArrayOf(0x21, 0x07))
                )
                Model.V11, Model.V11Y, Model.V12HS, Model.V12HT, Model.V12PRO, Model.V12S,
                Model.V13, Model.V13PRO, Model.V14g, Model.V14s, Model.V9,
                Model.UNKNOWN -> WheelCommand.SendBytes(
                    buildMessage(Flag.DEFAULT, Command.REAL_TIME_INFO, byteArrayOf())
                )
            }
        }
    }

    /**
     * Cycle through BMS status and voltage requests for each battery.
     * Single-battery: 2-step cycle (status, voltages).
     * Dual-battery (V13): 4-step. Quad-battery (V14): 8-step.
     */
    private fun getNextBmsPollCommand(): WheelCommand {
        val battCount = model.batteryCount
        val cycleLen = battCount * 2 // status + voltages per battery
        val step = bmsPollCounter % cycleLen
        bmsPollCounter++

        val batteryNum = step / 2 // 0, 1, 2, or 3
        val batteryId = BmsId.BATTERY_1 + batteryNum
        val subCmd = if (step % 2 == 0) 0x01 else 0x02 // status then voltages

        return WheelCommand.SendBytes(buildBmsRequest(batteryId, subCmd))
    }

    /**
     * Returns the next init command that still needs a response.
     */
    private fun getNextInitRetryCommand(): WheelCommand {
        if (!isModelDetected) {
            // Alternate between standard and extended init on each retry
            return if (keepAliveCounter % 8 == 0) {
                WheelCommand.SendBytes(
                    buildMessage(Flag.EXTENDED, Command.MAIN_INFO, byteArrayOf(0x21, 0x06))
                )
            } else {
                WheelCommand.SendBytes(
                    buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x01))
                )
            }
        }
        return when {
            serialNumber.isEmpty() ->
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x02)))
            else ->
                WheelCommand.SendBytes(buildMessage(Flag.INITIAL, Command.MAIN_INFO, byteArrayOf(0x06)))
        }
    }

    // ==================== BMS Request Building ====================

    /**
     * Build a BMS request message.
     * @param batteryId BMS battery ID (0x24-0x27)
     * @param subCmd Sub-command: 0x04=serial, 0x01=status, 0x02=voltages
     */
    private fun buildBmsRequest(batteryId: Int, subCmd: Int): ByteArray {
        return buildMessage(
            Flag.EXTENDED,
            Command.MAIN_INFO,
            byteArrayOf(batteryId.toByte(), subCmd.toByte())
        )
    }

    // ==================== Message Building ====================

    companion object {
        internal fun isFirmwareAtLeast(version: String, major: Int, minor: Int): Boolean {
            val parts = version.split(".")
            if (parts.size < 2) return false
            val fwMajor = parts[0].toIntOrNull() ?: return false
            val fwMinor = parts[1].toIntOrNull() ?: return false
            return fwMajor > major || (fwMajor == major && fwMinor >= minor)
        }

        /** Commands supported by all InMotion V2 models. */
        val BASE_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.LIGHT_MODE to 0,
            SettingsCommandId.LOCK to 0,
            SettingsCommandId.POWER_OFF to 0,
            SettingsCommandId.CALIBRATE to 0,
            SettingsCommandId.HANDLE_BUTTON to 0,
            SettingsCommandId.RIDE_MODE to 0,
            SettingsCommandId.SPEAKER_VOLUME to 0,
            SettingsCommandId.PEDAL_TILT to 0,
            SettingsCommandId.PEDAL_SENSITIVITY to 0,
            SettingsCommandId.MAX_SPEED to 0,
            SettingsCommandId.TRANSPORT_MODE to 0,
            SettingsCommandId.DRL to 0,
            SettingsCommandId.GO_HOME_MODE to 0,
            SettingsCommandId.FANCIER_MODE to 0,
            SettingsCommandId.MUTE to 0,
            SettingsCommandId.LIGHT_BRIGHTNESS to 0,
            SettingsCommandId.STANDBY_TIME to 0,
        )

        /** V11/V11Y-only commands. */
        val V11_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.FAN to 0,
            SettingsCommandId.FAN_QUIET to 0,
        )

        /** V12 family commands. */
        val V12_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.AUTO_HEADLIGHT to 0,
            SettingsCommandId.SCREEN_AUTO_OFF to 0,
        )

        /** V13/V14 commands. */
        val V13_V14_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.AUTO_HEADLIGHT to 0,
            SettingsCommandId.BERM_ANGLE_MODE to 0,
            SettingsCommandId.SAFE_SPEED_LIMIT to 0,
            SettingsCommandId.LIGHT_EFFECT_MODE to 0,
        )

        /** V14-only commands (on top of V13_V14_COMMANDS). */
        val V14_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.TWO_BATTERY_MODE to 0,
        )

        /** P6-specific additions. Also used as a negative filter to remove LIGHT_MODE and LIGHT_BRIGHTNESS from BASE. */
        val P6_COMMANDS: CapabilityMap = mapOf(
            SettingsCommandId.LOGO_LIGHT_BRIGHTNESS to 0,
            SettingsCommandId.TAIL_LIGHT_MODE to 0,
            SettingsCommandId.TURN_SIGNAL_MODE to 0,
            SettingsCommandId.AUTO_HEADLIGHT to 0,
            SettingsCommandId.SCREEN_AUTO_OFF to 0,
            SettingsCommandId.CHARGE_LIMIT to 0,
            SettingsCommandId.BALANCE_ANGLE to 0,
            SettingsCommandId.AUTO_LOCK to 0,
            SettingsCommandId.CHARGING_CURRENT to 0,
            SettingsCommandId.IGNORE_TIRE_PRESSURE to 0,
            SettingsCommandId.MIN_TIRE_PRESSURE to 0,
            SettingsCommandId.RIDE_CONNECT_SWITCH to 0,
            SettingsCommandId.RIDE_CONNECT_LOW_BATTERY to 0,
            SettingsCommandId.SPEED_TILTBACK_ENABLE to 0,
        )
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
            val check = ProtocolChecksums.xorChecksum(buffer.toByteArray())

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
