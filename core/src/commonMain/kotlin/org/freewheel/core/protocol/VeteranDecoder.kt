package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilityMap
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.SmartBms
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.domain.resolveAt
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.currentTimeMillis
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.offsetAt

/**
 * Looks up SOC percentage from a voltage-to-SOC table.
 * Table has 100 entries (index 0-99 = 0%-99%), each value is the minimum voltage × 100
 * for that SOC level. Returns 100 if voltage exceeds the last entry.
 */
internal fun lookupSoc(voltage: Int, table: IntArray): Int {
    if (voltage < table[0]) return 0
    if (voltage >= table[table.lastIndex]) return 100
    // Binary search: find highest index where voltage >= table[index]
    var low = 0
    var high = table.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (table[mid] <= voltage) low = mid else high = mid - 1
    }
    // Interpolate between low and low+1
    val range = table[low + 1] - table[low]
    val fraction = if (range > 0) (voltage - table[low]).toDouble() / range else 0.0
    return (low + fraction).roundToInt()
}

/**
 * Simple CRC32 calculation for KMP.
 */
internal fun veteranCrc32(data: ByteArray, offset: Int, length: Int): Long {
    var crc = 0xFFFFFFFFL
    for (i in offset until offset + length) {
        val byte = data[i].toLong() and 0xFF
        crc = crc xor byte
        for (j in 0 until 8) {
            crc = if ((crc and 1L) == 1L) {
                (crc ushr 1) xor 0xEDB88320L
            } else {
                crc ushr 1
            }
        }
    }
    return crc xor 0xFFFFFFFFL
}

/**
 * Frame unpacker for Veteran/Leaperkim wheels.
 *
 * Frame format:
 * - Bytes 0-2: Header (DC 5A 5C)
 * - Byte 3: Length
 * - Bytes 4+: Data payload
 * - Last 4 bytes: CRC32 (for newer firmware)
 */
internal class VeteranUnpacker : Unpacker {

    private enum class State {
        UNKNOWN,
        COLLECTING,
        LEN_SEARCH,
        DONE
    }

    private var buffer = ByteArrayBuilder()
    private var old1 = 0
    private var old2 = 0
    private var len = 0
    private var state = State.UNKNOWN
    private var usingCrc = false

    // Error counters (persist across reset(), cleared by resetStats())
    private var _errorResets = 0
    private var _bytesDiscarded = 0

    override val stats: UnpackerStats get() = UnpackerStats(_errorResets, _bytesDiscarded)

    override fun resetStats() {
        _errorResets = 0
        _bytesDiscarded = 0
    }

    override fun reset() {
        // Note: buffer is intentionally NOT cleared here.
        // VeteranUnpacker calls reset() after frame assembly but before
        // getBuffer() — the buffer must remain intact until the caller
        // reads it. The buffer is cleared when a new header is detected.
        old1 = 0
        old2 = 0
        state = State.UNKNOWN
    }

    override fun getBuffer(): ByteArray = buffer.toByteArray()

    override fun addChar(c: Int): Boolean {
        val byte = c and 0xFF

        when (state) {
            State.COLLECTING -> {
                val bsize = buffer.size()

                // Data verification checks
                if ((bsize == 22 && byte != 0x00) ||
                    (bsize == 30 && !(byte == 0x00 || byte == 0x07)) ||
                    (bsize == 23 && (byte and 0xFE) != 0x00)) {
                    // Data validation failed — partial frame discarded
                    _errorResets++
                    _bytesDiscarded += bsize + 1  // buffer size + current byte
                    state = State.DONE
                    reset()
                    return false
                }

                buffer.write(byte)

                if (bsize == len + 3) {
                    state = State.DONE
                    reset()

                    // Check CRC32 for new format
                    if (len > 38 || usingCrc) {
                        val data = getBuffer()
                        val calcCrc = veteranCrc32(data, 0, len)
                        val providedCrc = ByteUtils.intFromBytesBE(data, len)
                        if (calcCrc == providedCrc) {
                            usingCrc = true
                            return true
                        } else {
                            // CRC mismatch — fully assembled frame discarded
                            _errorResets++
                            _bytesDiscarded += data.size
                            return false
                        }
                    }
                    return true // old format without CRC
                }
            }

            State.LEN_SEARCH -> {
                buffer.write(byte)
                len = byte
                state = State.COLLECTING
                old2 = old1
                old1 = byte
            }

            else -> {
                // Looking for header (DC 5A 5C)
                if (byte == 0x5C && old1 == 0x5A && old2 == 0xDC) {
                    buffer.clear()
                    buffer.write(0xDC)
                    buffer.write(0x5A)
                    buffer.write(0x5C)
                    state = State.LEN_SEARCH
                } else if (byte == 0x5A && old1 == 0xDC) {
                    old2 = old1
                } else {
                    old2 = 0
                }
                old1 = byte
            }
        }

        return false
    }

}

/**
 * Veteran/Leaperkim protocol decoder.
 *
 * Supports:
 * - Sherman, Sherman S, Sherman L
 * - Abrams
 * - Patton, Patton S
 * - Lynx, Lynx S
 * - Oryx
 * - Nosfet Apex/Aero/Aeon
 *
 * Data starts streaming immediately — no init commands needed.
 * Model is detected from the mVer byte in the first valid frame.
 *
 * Frame format (reassembled by VeteranUnpacker):
 * - Bytes 0-1:  Voltage (BE, ÷100)
 * - Bytes 2-3:  Speed (BE, ÷10, signed)
 * - Bytes 4-7:  Distance (BE, ÷1000)
 * - Bytes 8-9:  Phase current (BE, signed)
 * - Bytes 10-11: Temperature (BE, ÷340 + 36.53)
 * - Byte 20:    mVer (model identifier)
 *   0/1=Sherman, 2=Abrams, 3=Sherman S, 4=Patton, 5=Lynx, 6=Sherman L,
 *   7=Patton S, 8=Oryx, 9=Lynx S, 42=Apex, 43=Aero, 44=Aeon
 *
 * State machine: none — always ready after first frame with valid mVer.
 *
 * Thread-safe: All methods except [buildCommand] are called from the WCM
 * event loop (single-threaded). [buildCommand] reads mVer from the immutable
 * [DecoderState] snapshot, so no lock is needed.
 */
class VeteranDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.VETERAN

    private data class SubTypeData(
        val roll: Double? = null,
        val lockState: Int? = null,
        val batteryOverride: Int? = null,
        val highSpeedMode: Boolean? = null,
        val lowVoltageMode: Boolean? = null,
        val voltageCorrection: Int? = null,
        val transportMode: Boolean? = null,
        val keyTone: Int? = null,
        val pedalHardness: Int? = null,
        val stopSpeed: Int? = null,
        val stopPowerRate: Int? = null,
        val screenBacklightRate: Int? = null,
        val maxChargeVol: Int? = null,
        val brakePressureAlarm: Int? = null,
        val lateralCutoffAngle: Int? = null,
        val dynamicAssist: Int? = null,
        val accelerationLimit: Int? = null,
        val chargeVoltageBase: Int? = null,
        val wheelDisplayUnit: Int? = null,
    )

    private val unpacker = VeteranUnpacker()
    private var lastPacketTime = 0L
    private var hasSyncedTime = false
    private var mVer = 0
    private var version = ""
    private var bms1 = SmartBms()
    private var bms2 = SmartBms()
    private var receivingLog = false

    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
        val currentTime = currentTimeMillis()

        // Reset unpacker if too much time has passed (packet loss)
        if (currentTime - lastPacketTime > WAITING_TIME) {
            unpacker.reset()
        }
        lastPacketTime = currentTime

        val loopResult = decodeFrames(data, unpacker, currentState) { buffer, state ->
            processFrame(buffer, state, config)
        }

        return when (loopResult) {
            is DecodeResult.Success -> {
                val extraCommands = if (!hasSyncedTime && mVer >= 3) {
                    hasSyncedTime = true
                    buildTimeSyncCommands()
                } else emptyList()
                val bmsSnapshot = BmsState(bms1 = bms1.toSnapshot(), bms2 = bms2.toSnapshot())
                // Ensure wheelType is VETERAN
                val resolvedIdentity = when {
                    loopResult.data.identity != null && loopResult.data.identity.wheelType == WheelType.Unknown ->
                        loopResult.data.identity.copy(wheelType = WheelType.VETERAN)
                    loopResult.data.identity != null -> loopResult.data.identity
                    currentState.identity.wheelType == WheelType.Unknown ->
                        currentState.identity.copy(wheelType = WheelType.VETERAN)
                    else -> null
                }
                DecodeResult.Success(DecodedData(
                    telemetry = loopResult.data.telemetry,
                    identity = resolvedIdentity?.takeIf { it != currentState.identity },
                    bms = bmsSnapshot.takeIf { it != currentState.bms },
                    settings = loopResult.data.settings?.takeIf { it != currentState.settings },
                    commands = loopResult.data.commands + extraCommands,
                    hasNewData = loopResult.data.hasNewData,
                    frameTypes = loopResult.data.frameTypes,
                    logEntries = loopResult.data.logEntries
                ))
            }
            is DecodeResult.Buffering -> loopResult
            is DecodeResult.Unhandled -> loopResult
        }
    }

    private fun processFrame(
        buff: ByteArray,
        currentState: DecoderState,
        config: DecoderConfig
    ): FrameOutcome {
        if (buff.size < 36) return FrameOutcome.Unrecognized("size=${buff.size}")

        val veteranNegative = config.gotwayNegative
        val tel = currentState.telemetry
        val vet = currentState.settings as? WheelSettings.Veteran ?: WheelSettings.Veteran()

        val voltage = ByteUtils.shortFromBytesBE(buff, 4)
        var speed = ByteUtils.signedShortFromBytesBE(buff, 6) * 10
        val distance = ByteUtils.intFromBytesRevBE(buff, 8).toLong()
        val totalDistance = ByteUtils.intFromBytesRevBE(buff, 12).toLong()
        var phaseCurrent = ByteUtils.signedShortFromBytesBE(buff, 16) * 10
        val temperature = ByteUtils.signedShortFromBytesBE(buff, 18)
        val autoOffSec = ByteUtils.shortFromBytesBE(buff, 20)
        val chargeMode = ByteUtils.shortFromBytesBE(buff, 22)
        val speedAlert = ByteUtils.shortFromBytesBE(buff, 24) * 10
        val speedTiltback = ByteUtils.shortFromBytesBE(buff, 26) * 10
        val ver = ByteUtils.shortFromBytesBE(buff, 28)
        mVer = ver / 1000
        version = "${ver / 1000}.${(ver % 1000) / 100}.${ver % 100}".padStart(9, '0')
        // Pedals mode: valid values are 0/1/2 (hard/medium/soft).
        // Nosfet (mVer >= 42) firmware repurposes bytes 30-31 — byte 30 is 0x07,
        // byte 31 is 0x80 (not-supported marker), giving 1920 as a 16-bit read.
        // Treat any value > 2 as unknown.
        val pedalsRaw = ByteUtils.shortFromBytesBE(buff, 30)
        val pedalsMode = if (pedalsRaw in 0..2) pedalsRaw else -1
        val pitchAngle = ByteUtils.signedShortFromBytesBE(buff, 32)
        val hwPwm = ByteUtils.shortFromBytesBE(buff, 34)
        // Battery temp mode: bitmask where 111=normal, 100/101/110=high-temp zone.
        // Nosfet firmware writes 0x80 (not-supported) at byte 36, producing garbage
        // 16-bit values. Cap to valid range.
        val batteryTempRaw = if (buff.size >= 38) ByteUtils.shortFromBytesBE(buff, 36) else 0
        val batteryTempMode = if (batteryTempRaw in 0..111) batteryTempRaw else 0

        // Process SmartBMS data for newer wheels
        if (mVer >= 5 && buff.size > 46) {
            processBmsData(buff)
        }

        // Calculate battery percentage
        val battery = calculateBatteryPercent(voltage, config.useCustomPercents)

        // Apply polarity
        if (veteranNegative == 0) {
            speed = abs(speed)
            phaseCurrent = abs(phaseCurrent)
        } else {
            speed *= veteranNegative
            phaseCurrent *= veteranNegative
        }

        // Calculate current and power
        val calculatedPwm: Double
        val output: Int
        if (config.hwPwmEnabled) {
            output = hwPwm
            calculatedPwm = hwPwm / 10000.0
        } else {
            val rotRatio = config.rotationSpeed.toDouble() / config.rotationVoltage
            calculatedPwm = if (rotRatio * voltage * config.powerFactor != 0.0)
                speed.toDouble() / (rotRatio * voltage * config.powerFactor)
            else 0.0
            output = (calculatedPwm * 10000).roundToInt()
        }
        val current = (calculatedPwm * phaseCurrent).roundToInt()
        val power = ((current / 100.0) * voltage).roundToInt()

        // Parse sub-type extended data for newer wheels
        val subData = if (mVer >= 5 && buff.size > 46) parseSubTypeData(buff) else null

        val newTel = tel.copy(
            speed = speed,
            voltage = voltage,
            phaseCurrent = phaseCurrent,
            current = current,
            power = power,
            temperature = temperature,
            wheelDistance = distance,
            totalDistance = totalDistance,
            batteryLevel = subData?.batteryOverride ?: battery,
            chargingStatus = chargeMode,
            output = output,
            calculatedPwm = calculatedPwm,
            angle = pitchAngle / 100.0,
            roll = subData?.roll ?: tel.roll
        )

        val newIdentity = currentState.identity.copy(
            version = version,
            model = getModelName(),
            wheelType = WheelType.VETERAN
        )

        var newSettings = vet.copy(
            tiltBackSpeed = speedTiltback / 10,
            alertSpeed = speedAlert / 10,
            autoOffTime = autoOffSec,
            pedalsMode = pedalsMode,
            batteryTempMode = batteryTempMode,
            mVer = mVer
        )

        // Merge sub-type settings data
        if (subData != null) {
            newSettings = newSettings.copy(
                lockState = subData.lockState ?: vet.lockState,
                highSpeedMode = subData.highSpeedMode ?: vet.highSpeedMode,
                lowVoltageMode = subData.lowVoltageMode ?: vet.lowVoltageMode,
                voltageCorrection = subData.voltageCorrection ?: vet.voltageCorrection,
                transportMode = subData.transportMode ?: vet.transportMode,
                keyTone = subData.keyTone ?: vet.keyTone,
                pedalSensitivity = subData.pedalHardness ?: vet.pedalSensitivity,
                stopSpeed = subData.stopSpeed ?: vet.stopSpeed,
                pwmLimit = subData.stopPowerRate ?: vet.pwmLimit,
                screenBacklight = subData.screenBacklightRate ?: vet.screenBacklight,
                maxChargeVoltage = subData.maxChargeVol ?: vet.maxChargeVoltage,
                brakePressureAlarm = subData.brakePressureAlarm ?: vet.brakePressureAlarm,
                lateralCutoffAngle = subData.lateralCutoffAngle ?: vet.lateralCutoffAngle,
                dynamicAssist = subData.dynamicAssist ?: vet.dynamicAssist,
                accelerationLimit = subData.accelerationLimit ?: vet.accelerationLimit,
                chargeVoltageBase = subData.chargeVoltageBase ?: vet.chargeVoltageBase,
                wheelDisplayUnit = subData.wheelDisplayUnit ?: vet.wheelDisplayUnit,
            )
        }

        // Parse event log entries when in log-receiving mode
        val pNum = if (buff.size > 46) buff[46].toInt() else -1
        val logEntries = if (receivingLog) parseLogEntries(buff, pNum) else emptyList()

        return FrameOutcome.Processed(FrameResult(
            telemetry = newTel,
            identity = newIdentity,
            settings = newSettings,
            hasNewData = true,
            frameType = "TELEMETRY",
            logEntries = logEntries
        ))
    }

    private fun parseSubTypeData(buff: ByteArray): SubTypeData? {
        if (buff.size <= 46) return null
        val pNum = buff[46].toInt()

        return when (pNum) {
            0, 4 -> {
                // Roll angle (left-right) at bytes 67-68
                if (buff.size > 68) {
                    val rollRaw = ByteUtils.signedShortFromBytesBE(buff, 67)
                    SubTypeData(roll = rollRaw / 100.0)
                } else null
            }
            5 -> {
                // Lock state at byte 51
                if (buff.size > 51) {
                    SubTypeData(lockState = buff[51].toInt() and 0xFF)
                } else null
            }
            2 -> {
                // Fall protection angle at byte 47
                val angle = if (buff.size > 47) {
                    val raw = buff[47].toInt() and 0xFF
                    if (raw == 0) null else raw  // 0 = not set
                } else null
                // Battery % override at byte 50
                if (buff.size > 50) {
                    val pct = buff[50].toInt() and 0xFF
                    if (pct in 0..100) SubTypeData(batteryOverride = pct, lateralCutoffAngle = angle)
                    else SubTypeData(lateralCutoffAngle = angle)
                } else SubTypeData(lateralCutoffAngle = angle)
            }
            8 -> parseControlSettings(buff)
            else -> null
        }
    }

    private fun parseControlSettings(buff: ByteArray): SubTypeData {
        // Control settings from sub-type 8
        // 0x80 (128) means "not supported" for each field
        val NOT_SUPPORTED = 0x80

        fun readUnsigned(offset: Int): Int? {
            if (buff.size <= offset) return null
            val raw = buff[offset].toInt() and 0xFF
            return if (raw == NOT_SUPPORTED) null else raw
        }

        fun readSigned(offset: Int): Int? {
            if (buff.size <= offset) return null
            val raw = buff[offset].toInt() // signed byte (-128 to 127)
            return if ((raw and 0xFF) == NOT_SUPPORTED) null else raw
        }

        fun readBool(offset: Int): Boolean? {
            val raw = readUnsigned(offset) ?: return null
            return raw != 0
        }

        return SubTypeData(
            pedalHardness = readUnsigned(50),         // byte 50: pedal hardness 0-100
            stopSpeed = readUnsigned(52),             // byte 52: stop speed (raw, +10 encoding)
            stopPowerRate = readUnsigned(53),          // byte 53: PWM limit (raw, +30 encoding)
            screenBacklightRate = readUnsigned(55),    // byte 55: screen backlight 0-100%
            transportMode = readBool(57),              // byte 57: transport mode
            wheelDisplayUnit = readUnsigned(58),       // byte 58: wheel display unit (0=km, 1=miles)
            voltageCorrection = readSigned(59),        // byte 59: signed byte -15 to +15
            lowVoltageMode = readBool(60),             // byte 60: low voltage mode
            highSpeedMode = readBool(61),              // byte 61: high speed mode
            keyTone = readUnsigned(63),                // byte 63: key tone 0-100%
            maxChargeVol = readUnsigned(64),           // byte 64: max charge voltage (0-120)
            chargeVoltageBase = readUnsigned(65)?.let { if (it == 0) 145 else it }, // byte 65: base voltage for charge limit
            dynamicAssist = readUnsigned(66),          // byte 66: dynamic assist 0-100%
            accelerationLimit = readUnsigned(68),      // byte 68: acceleration limit 0-100%
            brakePressureAlarm = readUnsigned(69),     // byte 69: brake pressure alarm (80-125%)
        )
    }

    private fun processBmsData(buff: ByteArray) {
        val pNum = buff[46].toInt()
        val bmsNum = if (pNum < 4) 1 else 2
        val bms = if (bmsNum == 1) bms1 else bms2

        when (pNum) {
            0, 4 -> {
                // BMS current data
                if (buff.size > 72) {
                    bms1.current = ByteUtils.signedShortFromBytesBE(buff, 69) / 100.0
                    bms2.current = ByteUtils.signedShortFromBytesBE(buff, 71) / 100.0
                }
            }
            1, 5 -> {
                // First 15 cells
                for (i in 0 until 15) {
                    val cell = ByteUtils.signedShortFromBytesBE(buff, 53 + i * 2)
                    bms.cells[i] = cell / 1000.0
                }
            }
            2, 6 -> {
                // Cells 15-29
                for (i in 0 until 15) {
                    val cell = ByteUtils.shortFromBytesBE(buff, 53 + i * 2)
                    bms.cells[i + 15] = cell / 1000.0
                }
            }
            3, 7 -> {
                // Cells 30+ and temperatures
                for (i in 0 until 12) {
                    val offset = 59 + i * 2
                    if (offset < buff.size) {
                        val cell = ByteUtils.shortFromBytesBE(buff, offset)
                        bms.cells[i + 30] = cell / 1000.0
                    }
                }
                bms.temp1 = ByteUtils.signedShortFromBytesBE(buff, 47) / 100.0
                bms.temp2 = ByteUtils.signedShortFromBytesBE(buff, 49) / 100.0
                bms.temp3 = ByteUtils.signedShortFromBytesBE(buff, 51) / 100.0
                bms.temp4 = ByteUtils.signedShortFromBytesBE(buff, 53) / 100.0
                bms.temp5 = ByteUtils.signedShortFromBytesBE(buff, 55) / 100.0
                bms.temp6 = ByteUtils.signedShortFromBytesBE(buff, 57) / 100.0

                updateBmsCellStats(bms)
            }
        }
    }

    private fun updateBmsCellStats(bms: SmartBms) {
        val cellCount = getCellsForWheel()
        bms.cellNum = cellCount
        bms.minCell = bms.cells[0]
        bms.maxCell = bms.cells[0]
        bms.maxCellNum = 1
        bms.minCellNum = 1
        var totalVolt = 0.0

        for (i in 0 until cellCount) {
            val cell = bms.cells[i]
            totalVolt += cell
            if (cell > 0.0) {
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
        bms.voltage = totalVolt
        bms.avgCell = totalVolt / cellCount
    }

    private fun calculateBatteryPercent(voltage: Int, useBetterPercents: Boolean): Int {
        // Use official Leaperkim SOC lookup tables when available
        val table = if (useBetterPercents) getSocTable() else null
        if (table != null) return lookupSoc(voltage, table)

        // Piecewise-linear fallback
        return when {
            mVer < 4 -> { // Sherman, Abrams, Sherman S (100V)
                when {
                    voltage <= 7935 -> 0
                    voltage >= 9870 -> 100
                    else -> ((voltage - 7935) / 19.5).roundToInt()
                }
            }
            mVer == 4 || mVer == 7 -> { // Patton, Patton S (126V)
                when {
                    voltage <= 9918 -> 0
                    voltage >= 12337 -> 100
                    else -> ((voltage - 9918) / 24.2).roundToInt()
                }
            }
            mVer == 43 -> { // Nosfet Aero (126V, 2P — different curve than Patton 4P)
                when {
                    voltage <= 9918 -> 0
                    voltage >= 12337 -> 100
                    else -> ((voltage - 9918) / 24.2).roundToInt()
                }
            }
            mVer == 5 || mVer == 6 || mVer == 9 || mVer == 42 || mVer == 44 -> { // Lynx, Sherman L, Lynx S, Nosfet Apex/Aeon (151V)
                when {
                    voltage <= 11902 -> 0
                    voltage >= 14805 -> 100
                    else -> ((voltage - 11902) / 29.03).roundToInt()
                }
            }
            mVer == 8 -> { // Oryx (176V)
                when {
                    voltage <= 13886 -> 0
                    voltage >= 17272 -> 100
                    else -> ((voltage - 13886) / 34.125).roundToInt()
                }
            }
            else -> 1 // Unknown wheel, default to 1%
        }
    }

    /**
     * Returns the official Leaperkim SOC lookup table for the current model, or null
     * if no table is available. Tables map SOC index (0-99) to minimum voltage × 100.
     */
    private fun getSocTable(): IntArray? {
        return when (mVer) {
            0, 1 -> VeteranSocTables.SHERMAN_100V
            2 -> VeteranSocTables.SHERMAN_100V // Abrams (same 100.8V chemistry)
            3 -> VeteranSocTables.SHERMAN_100V // Sherman S (same 100.8V chemistry)
            4 -> VeteranSocTables.PATTON_126V
            7 -> VeteranSocTables.PATTON_126V // Patton S (same 126V chemistry/pack config)
            5 -> VeteranSocTables.LYNX_151V
            6 -> VeteranSocTables.LYNX_151V // Sherman L (same 151.2V chemistry)
            9 -> VeteranSocTables.LYNX_151V // Lynx S (same 151.2V chemistry)
            42 -> VeteranSocTables.LYNX_151V // Nosfet Apex (same 151.2V, same pack config)
            44 -> VeteranSocTables.LYNX_151V // Nosfet Aeon (same 151.2V, 36S)
            else -> null // Oryx, Nosfet Aero, unknown — use piecewise fallback
        }
    }

    private fun getCellsForWheel(): Int {
        return when {
            mVer == 4 || mVer == 7 || mVer == 43 -> 30 // Patton, Patton S, Aero
            mVer == 8 -> 42 // Oryx
            mVer == 5 || mVer == 6 || mVer == 9 || mVer == 42 || mVer == 44 -> 36 // Lynx, Sherman L, Lynx S, Apex, Aeon
            mVer >= 5 -> 36 // fallback for unknown mVer >= 5
            else -> 24 // Sherman, Abrams, Sherman S
        }
    }

    private fun getModelName(): String {
        return when (mVer) {
            0, 1 -> "Leaperkim Sherman"
            2 -> "Leaperkim Abrams"
            3 -> "Leaperkim Sherman S"
            4 -> "Leaperkim Patton"
            5 -> "Leaperkim Lynx"
            6 -> "Leaperkim Sherman L"
            7 -> "Leaperkim Patton S"
            8 -> "Leaperkim Oryx"
            9 -> "Leaperkim Lynx S"
            42 -> "Nosfet Apex"
            43 -> "Nosfet Aero"
            44 -> "Nosfet Aeon"
            else -> "Unknown"
        }
    }

    override fun isReady(): Boolean = mVer != 0

    override fun getCapabilities(): CapabilitySet {
        if (mVer == 0) return CapabilitySet()
        return CAPABILITY_MAP.resolveAt(
            firmwareLevel = mVer,
            detectedModel = getModelName(),
            firmwareVersion = version
        )
    }

    override fun getUnpackerStats(): UnpackerStats = unpacker.stats

    override fun reset() {
        unpacker.reset()
        lastPacketTime = 0
        hasSyncedTime = false
        mVer = 0
        version = ""
        bms1 = SmartBms()
        bms2 = SmartBms()
        receivingLog = false
    }

    /**
     * Build time sync commands to send on first connection.
     * Format: [4C 64 41 70 12 00 05 year-2000 month day hour min sec tz_offset] + CRC32
     * Official apps send twice with 2s delay.
     */
    private fun buildTimeSyncCommands(): List<WheelCommand> {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val dt = now.toLocalDateTime(tz)
        val tzOffsetHours = tz.offsetAt(now).totalSeconds / 3600
        val payload = byteArrayOf(
            0x4C, 0x64, 0x41, 0x70, 0x12, 0x00, 0x05,
            (dt.year - 2000).toByte(), dt.monthNumber.toByte(),
            dt.dayOfMonth.toByte(), dt.hour.toByte(),
            dt.minute.toByte(), dt.second.toByte(), tzOffsetHours.toByte()
        )
        val cmd = appendCrc32(payload)
        return listOf(
            WheelCommand.SendBytes(cmd),
            WheelCommand.SendDelayed(cmd, 2000L)
        )
    }

    /**
     * Build a lock/unlock command with time-based prefix and numeric password.
     *
     * Format: [LdAp header] [0x19] [0x00 0x05] [datetime 7B] [password 3B BE] [cmd] [0 0 0] + CRC32
     * - Command byte 0x19 = time sync (0x12) + 7
     * - Password is parsed as integer and encoded big-endian in 3 bytes
     * - Lock command: 0 = lock, 1 = unlock
     *
     * Reverse-engineered from Leaperkim app v1.4.8 Util.genPwdCmd().
     */
    private fun buildLockCommand(locked: Boolean, password: String): ByteArray {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val dt = now.toLocalDateTime(tz)
        val tzOffsetHours = tz.offsetAt(now).totalSeconds / 3600
        val pwd = password.toIntOrNull() ?: 0

        val payload = ByteArray(21)
        // LdAp header
        payload[0] = 0x4C; payload[1] = 0x64; payload[2] = 0x41; payload[3] = 0x70
        // Command byte: time sync (0x12) + 7 = 0x19
        payload[4] = 0x19
        payload[5] = 0x00; payload[6] = 0x05
        // Date/time
        payload[7] = (dt.year - 2000).toByte()
        payload[8] = dt.monthNumber.toByte()
        payload[9] = dt.dayOfMonth.toByte()
        payload[10] = dt.hour.toByte()
        payload[11] = dt.minute.toByte()
        payload[12] = dt.second.toByte()
        payload[13] = tzOffsetHours.toByte()
        // Password (3-byte big-endian)
        payload[14] = ((pwd shr 16) and 0xFF).toByte()
        payload[15] = ((pwd shr 8) and 0xFF).toByte()
        payload[16] = (pwd and 0xFF).toByte()
        // Lock command: 0 = lock, 1 = unlock
        payload[17] = if (locked) 0 else 1
        // Secondary parameter (zeros for lock/unlock)
        // payload[18..20] already zero from ByteArray init

        return appendCrc32(payload)
    }

    /**
     * Build event log request payload.
     *
     * Old format (LkAp): [4C 6B 41 70 14 01 80×9 01]
     * New format (LdAp): [4C 64 41 70 14 01 00 80×8 01]
     */
    private fun buildReadLogPayload(old: Boolean): ByteArray {
        return if (old) {
            buildVeteranCommand(0x14, 15, 0x01, byte5 = 0x01)
        } else {
            buildVeteranCommandNew(0x14, 15, 0x01)
        }
    }

    /**
     * Parse event log entries from a telemetry frame when in log-receiving mode.
     *
     * Sub-type 0/4: 2 basic entries (index + 2-byte content code)
     * Sub-type 32:  3 extended entries (index + content + 5 extra diagnostic bytes)
     * Sub-type 33:  1 full-detail entry (packed count/index + timestamp + content + extra values + GBK text)
     *
     * Reverse-engineered from Leaperkim app v1.4.8 BtManager.parseData().
     */
    internal fun parseLogEntries(buff: ByteArray, pNum: Int): List<EventLogEntry> {
        return when (pNum) {
            0, 4 -> parseLogBasic(buff)
            32 -> parseLogExtended(buff)
            33 -> parseLogDetailed(buff)
            else -> emptyList()
        }
    }

    /** Sub-type 0/4: 2 basic log entries at bytes 50-54. */
    private fun parseLogBasic(buff: ByteArray): List<EventLogEntry> {
        // Leaperkim app: receiverSingleFuLLData.size() - 4 > 54 → buff.size > 58
        if (buff.size <= 58) return emptyList()
        val entries = mutableListOf<EventLogEntry>()
        val index = buff[50].toInt() and 0xFF
        val content = ByteUtils.shortFromBytesBE(buff, 51)
        entries.add(EventLogEntry(index = index, contentCode = content))
        if (index < 255) {
            val content2 = ByteUtils.shortFromBytesBE(buff, 53)
            entries.add(EventLogEntry(index = index + 1, contentCode = content2))
        }
        return entries
    }

    /** Sub-type 32: 3 extended log entries at bytes 47-80, each with 5 extra bytes. */
    private fun parseLogExtended(buff: ByteArray): List<EventLogEntry> {
        if (buff.size <= 84) return emptyList()
        val entries = mutableListOf<EventLogEntry>()
        val index = buff[47].toInt() and 0xFF
        // Entry 1: bytes 47-58
        entries.add(EventLogEntry(
            index = index,
            contentCode = ByteUtils.shortFromBytesBE(buff, 48),
            extraBytes = buff.copyOfRange(54, 59)
        ))
        // Entry 2: bytes 59-69
        if (index < 255) {
            entries.add(EventLogEntry(
                index = index + 1,
                contentCode = ByteUtils.shortFromBytesBE(buff, 59),
                extraBytes = buff.copyOfRange(65, 70)
            ))
        }
        // Entry 3: bytes 70-80
        if (index < 254) {
            entries.add(EventLogEntry(
                index = index + 2,
                contentCode = ByteUtils.shortFromBytesBE(buff, 70),
                extraBytes = buff.copyOfRange(76, 81)
            ))
        }
        return entries
    }

    /** Sub-type 33: 1 detailed log entry with packed count/index, timestamp, extras, and GBK text. */
    private fun parseLogDetailed(buff: ByteArray): List<EventLogEntry> {
        if (buff.size <= 60) return emptyList()
        val b47 = buff[47].toInt() and 0xFF
        val b48 = buff[48].toInt() and 0xFF
        val b49 = buff[49].toInt() and 0xFF
        // Bit-packed: totalLogNum = b47*16 + b48/16, index = (b48%16)*256 + b49
        val totalLogNum = b47 * 16 + b48 / 16
        val index = (b48 % 16) * 256 + b49
        // Timestamp: 4 bytes at 50-53 → big-endian unsigned
        val timestamp = ByteUtils.intFromBytesBE(buff, 50)
        // Content code: bytes 54-55
        val contentCode = ByteUtils.shortFromBytesBE(buff, 54)
        // Extra count at byte 56
        val extraCount = buff[56].toInt() and 0xFF
        // Extra values: extraCount × 4 bytes starting at byte 57
        val extras = mutableListOf<Long>()
        for (i in 0 until extraCount) {
            val offset = 57 + i * 4
            if (offset + 3 >= buff.size) break
            var value = ByteUtils.intFromBytesBE(buff, offset)
            // Signed: values > 2^31 are negative
            if (value > 2147483648L) value -= 4294967296L
            extras.add(value)
        }
        // GBK text: null-terminated bytes after extra values
        val textStart = 57 + extraCount * 4
        val textBytes = mutableListOf<Byte>()
        var i = textStart
        while (i < buff.size - 4 && buff[i] != 0.toByte()) { // -4 for CRC
            textBytes.add(buff[i])
            i++
        }
        // GBK decode (falls back to platform default if GBK unavailable)
        val text = try {
            textBytes.toByteArray().decodeToString()
        } catch (_: Exception) {
            ""
        }

        return listOf(EventLogEntry(
            index = index,
            totalCount = totalLogNum,
            contentCode = contentCode,
            timestamp = timestamp,
            extras = extras,
            text = text
        ))
    }

    /**
     * Build a Veteran binary command with CRC32.
     * Format: [4C 6B 41 70] [cmdByte] [byte5] [padding 0x80...] [valueByte] + CRC32 (4 bytes BE)
     *
     * The value is placed at [valuePosition] (0-indexed), with 0x80 padding between byte 6 and the value.
     * The total payload (before CRC) is valuePosition + 1.
     */
    private fun buildVeteranCommand(cmdByte: Int, valuePosition: Int, value: Int, byte5: Int = 0x00): ByteArray {
        val payloadSize = valuePosition + 1
        val payload = ByteArray(payloadSize)
        payload[0] = 0x4C
        payload[1] = 0x6B
        payload[2] = 0x41
        payload[3] = 0x70
        payload[4] = cmdByte.toByte()
        payload[5] = byte5.toByte()
        // Fill positions 6..(payloadSize-2) with 0x80 padding
        for (i in 6 until payloadSize - 1) {
            payload[i] = 0x80.toByte()
        }
        payload[payloadSize - 1] = value.toByte()

        return appendCrc32(payload)
    }

    /**
     * Build a Veteran binary command with "LdAp" (new format) prefix and CRC32.
     * Format: [4C 64 41 70] [cmdByte] [byte5] [byte6] [padding 0x80...] [valueByte] + CRC32
     *
     * byte6: 0x02 for control toggles (transport, high speed, low power), 0x00 for other settings.
     * Used for newer settings that only support the "LdAp" format.
     */
    private fun buildVeteranCommandNew(cmdByte: Int, valuePosition: Int, value: Int, byte5: Int = 0x01, byte6: Int = 0x00): ByteArray {
        val payloadSize = valuePosition + 1
        val payload = ByteArray(payloadSize)
        payload[0] = 0x4C
        payload[1] = 0x64  // "LdAp" — new format
        payload[2] = 0x41
        payload[3] = 0x70
        payload[4] = cmdByte.toByte()
        payload[5] = byte5.toByte()
        if (payloadSize > 6) payload[6] = byte6.toByte()
        // Fill positions 7..(payloadSize-2) with 0x80 padding
        for (i in 7 until payloadSize - 1) {
            payload[i] = 0x80.toByte()
        }
        payload[payloadSize - 1] = value.toByte()

        return appendCrc32(payload)
    }

    /**
     * Append 4-byte big-endian CRC32 to a byte array.
     */
    private fun appendCrc32(data: ByteArray): ByteArray {
        val crc = veteranCrc32(data, 0, data.size)
        val result = ByteArray(data.size + 4)
        data.copyInto(result)
        result[data.size] = ((crc shr 24) and 0xFF).toByte()
        result[data.size + 1] = ((crc shr 16) and 0xFF).toByte()
        result[data.size + 2] = ((crc shr 8) and 0xFF).toByte()
        result[data.size + 3] = (crc and 0xFF).toByte()
        return result
    }

    /**
     * Check if a command requiring a minimum firmware level is supported.
     * Uses the [CAPABILITY_MAP] as the single source of truth.
     */
    private fun isSupportedAt(commandId: SettingsCommandId, firmwareVer: Int): Boolean {
        val minVer = CAPABILITY_MAP[commandId] ?: return false
        return firmwareVer >= minVer
    }

    override fun buildCommand(command: WheelCommand, state: DecoderState?): List<WheelCommand> {
        val ver = (state?.settings as? WheelSettings.Veteran)?.mVer ?: 0
        return buildCommandWithVer(command, ver)
    }

    private fun buildCommandWithVer(command: WheelCommand, ver: Int): List<WheelCommand> {
        return when (command) {
            is WheelCommand.Beep -> {
                if (ver < 3) {
                    listOf(WheelCommand.SendBytes("b".encodeToByteArray()))
                } else {
                    listOf(WheelCommand.SendBytes(buildVeteranCommand(0x0E, 9, 0x01)))
                }
            }
            is WheelCommand.SetLight -> {
                if (ver < 3) {
                    listOf(WheelCommand.SendBytes(
                        if (command.enabled) "SetLightON".encodeToByteArray()
                        else "SetLightOFF".encodeToByteArray()
                    ))
                } else {
                    listOf(WheelCommand.SendBytes(
                        buildVeteranCommand(0x0D, 8, if (command.enabled) 1 else 0, byte5 = 0x01)
                    ))
                }
            }
            is WheelCommand.SetPedalsMode -> {
                if (ver < 3) {
                    val cmd = when (command.mode) {
                        0 -> "SETh"; 1 -> "SETm"; 2 -> "SETs"
                        else -> return emptyList()
                    }
                    listOf(WheelCommand.SendBytes(cmd.encodeToByteArray()))
                } else {
                    val value = when (command.mode) {
                        0 -> 3; 1 -> 2; 2 -> 1  // hard/medium/soft
                        else -> return emptyList()
                    }
                    listOf(WheelCommand.SendBytes(buildVeteranCommand(0x0C, 7, value, byte5 = 0x01)))
                }
            }
            is WheelCommand.SetAlarmSpeed -> {
                if (!isSupportedAt(SettingsCommandId.ALARM_SPEED_1, ver)) return emptyList()
                val v = command.speed + 10
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x11, 12, v, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x11, 12, v))
                )
            }
            is WheelCommand.SetPedalTilt -> {
                if (!isSupportedAt(SettingsCommandId.PEDAL_TILT, ver)) return emptyList()
                val v = command.angle + 80
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x10, 11, v, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x10, 11, v))
                )
            }
            is WheelCommand.SetTransportMode -> {
                if (!isSupportedAt(SettingsCommandId.TRANSPORT_MODE, ver)) return emptyList()
                val value = if (command.enabled) 1 else 0
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x16, 17, value, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x16, 17, value, byte6 = 0x02))
                )
            }
            is WheelCommand.SetSpeakerVolume -> emptyList()
            is WheelCommand.SetHighSpeedMode -> {
                if (!isSupportedAt(SettingsCommandId.HIGH_SPEED_MODE, ver)) return emptyList()
                val value = if (command.enabled) 1 else 0
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x1A, 21, value, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x1A, 21, value, byte6 = 0x02))
                )
            }
            is WheelCommand.SetLowVoltageMode -> {
                if (!isSupportedAt(SettingsCommandId.LOW_VOLTAGE_MODE, ver)) return emptyList()
                val value = if (command.enabled) 1 else 0
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x19, 20, value, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x19, 20, value, byte6 = 0x02))
                )
            }
            is WheelCommand.SetKeyTone -> {
                if (!isSupportedAt(SettingsCommandId.KEY_TONE, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x1C, 23, command.value, byte6 = 0x02)))
            }
            is WheelCommand.PowerOff -> {
                if (!isSupportedAt(SettingsCommandId.POWER_OFF, ver)) return emptyList()
                val payload = byteArrayOf(
                    0x4C, 0x6B, 0x41, 0x70, 0x16, 0x01,
                    0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                    0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                    0x80.toByte(), 0x80.toByte(), 0x01, 0x80.toByte()
                )
                listOf(WheelCommand.SendBytes(appendCrc32(payload)))
            }
            is WheelCommand.ResetTrip -> {
                listOf(WheelCommand.SendBytes("CLEARMETER".encodeToByteArray()))
            }
            is WheelCommand.SetVeteranLock -> {
                listOf(WheelCommand.SendBytes(buildLockCommand(command.locked, command.password)))
            }
            is WheelCommand.RequestEventLog -> {
                receivingLog = true
                listOf(
                    WheelCommand.SendBytes(appendCrc32(buildReadLogPayload(old = true))),
                    WheelCommand.SendBytes(appendCrc32(buildReadLogPayload(old = false)))
                )
            }
            is WheelCommand.SetScreenBacklight -> {
                if (!isSupportedAt(SettingsCommandId.SCREEN_BACKLIGHT, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x14, 15, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetStopSpeed -> {
                if (!isSupportedAt(SettingsCommandId.STOP_SPEED, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x11, 12, command.speed, byte6 = 0x02)))
            }
            is WheelCommand.SetVeteranPwmLimit -> {
                if (!isSupportedAt(SettingsCommandId.VETERAN_PWM_LIMIT, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x12, 13, command.limit, byte6 = 0x02)))
            }
            is WheelCommand.SetVoltageCorrection -> {
                if (!isSupportedAt(SettingsCommandId.VOLTAGE_CORRECTION, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x18, 19, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetMaxChargeVoltage -> {
                if (!isSupportedAt(SettingsCommandId.MAX_CHARGE_VOLTAGE, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x1D, 24, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetBrakePressureAlarm -> {
                if (!isSupportedAt(SettingsCommandId.BRAKE_PRESSURE_ALARM, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x22, 29, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetLateralCutoffAngle -> {
                if (!isSupportedAt(SettingsCommandId.LATERAL_CUTOFF_ANGLE, ver)) return emptyList()
                listOf(
                    WheelCommand.SendBytes(buildVeteranCommand(0x16, 17, command.angle, byte5 = 0x01)),
                    WheelCommand.SendBytes(buildVeteranCommandNew(0x16, 17, command.angle))
                )
            }
            is WheelCommand.Calibrate -> {
                if (!isSupportedAt(SettingsCommandId.CALIBRATE, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x15, 16, 0x01, byte6 = 0x02)))
            }
            is WheelCommand.SetDynamicAssist -> {
                if (!isSupportedAt(SettingsCommandId.DYNAMIC_ASSIST, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x1F, 26, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetAccelerationLimit -> {
                if (!isSupportedAt(SettingsCommandId.ACCELERATION_LIMIT, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x21, 28, command.value, byte6 = 0x02)))
            }
            is WheelCommand.SetWheelDisplayUnit -> {
                if (!isSupportedAt(SettingsCommandId.WHEEL_DISPLAY_UNIT, ver)) return emptyList()
                listOf(WheelCommand.SendBytes(buildVeteranCommandNew(0x17, 18, if (command.miles) 1 else 0, byte6 = 0x02)))
            }
            else -> emptyList()
        }
    }

    override fun getInitCommands(): List<WheelCommand> = emptyList()

    companion object {
        private const val WAITING_TIME = 100L

        /** Single source of truth for Veteran command support by mVer. */
        val CAPABILITY_MAP: CapabilityMap = mapOf(
            // mVer 0+ (all models — ASCII protocol fallback)
            SettingsCommandId.LIGHT_MODE to 0,
            SettingsCommandId.PEDALS_MODE to 0,
            SettingsCommandId.LOCK to 0,
            SettingsCommandId.RESET_TRIP to 0,

            // mVer 3+ (LkAp/LdAp binary protocol)
            SettingsCommandId.ALARM_SPEED_1 to 3,
            SettingsCommandId.PEDAL_TILT to 3,
            SettingsCommandId.TRANSPORT_MODE to 3,
            SettingsCommandId.HIGH_SPEED_MODE to 3,
            SettingsCommandId.LOW_VOLTAGE_MODE to 3,
            SettingsCommandId.KEY_TONE to 3,
            SettingsCommandId.SCREEN_BACKLIGHT to 3,
            SettingsCommandId.STOP_SPEED to 3,
            SettingsCommandId.VETERAN_PWM_LIMIT to 3,
            SettingsCommandId.VOLTAGE_CORRECTION to 3,
            SettingsCommandId.MAX_CHARGE_VOLTAGE to 3,
            SettingsCommandId.BRAKE_PRESSURE_ALARM to 3,
            SettingsCommandId.LATERAL_CUTOFF_ANGLE to 3,
            SettingsCommandId.DYNAMIC_ASSIST to 3,
            SettingsCommandId.ACCELERATION_LIMIT to 3,
            SettingsCommandId.WHEEL_DISPLAY_UNIT to 3,
            SettingsCommandId.CALIBRATE to 3,
            SettingsCommandId.POWER_OFF to 3,
        )
    }
}
