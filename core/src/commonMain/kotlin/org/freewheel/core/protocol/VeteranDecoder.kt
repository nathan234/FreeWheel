package org.freewheel.core.protocol

import org.freewheel.core.domain.SmartBms
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.currentTimeMillis
import org.freewheel.core.utils.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

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

    override fun reset() {
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
                    buffer = ByteArrayBuilder()
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
 * - Lynx
 * - Oryx
 * - Nosfet Apex/Aero
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
 *   0/1=Sherman, 2=Abrams, 3=Sherman S, 4=Patton, 5=Lynx, etc.
 *
 * State machine: none — always ready after first frame with valid mVer.
 *
 * This class is thread-safe.
 */
class VeteranDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.VETERAN
    private val stateLock = Lock()

    private data class SubTypeData(
        val roll: Double? = null,
        val lockState: Int? = null,
        val batteryOverride: Int? = null,
        val highSpeedMode: Boolean? = null,
        val lowVoltageMode: Boolean? = null,
        val speakerVolume: Int? = null,
        val transportMode: Boolean? = null,
        val keyTone: Int? = null,
        val pedalHardness: Int? = null,
    )

    private val unpacker = VeteranUnpacker()
    private var lastPacketTime = 0L
    private var mVer = 0
    private var bms1 = SmartBms()
    private var bms2 = SmartBms()

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
        return stateLock.withLock {
            val currentTime = currentTimeMillis()

            // Reset unpacker if too much time has passed (packet loss)
            if (currentTime - lastPacketTime > WAITING_TIME) {
                unpacker.reset()
            }
            lastPacketTime = currentTime

            decodeFrames(data, unpacker, currentState) { buffer, state ->
                processFrame(buffer, state, config)?.let {
                    FrameResult(it, hasNewData = true)
                }
            }?.let { result ->
                result.copy(newState = result.newState.copy(
                    bms1 = bms1.toSnapshot(),
                    bms2 = bms2.toSnapshot()
                ))
            }
        }
    }

    private fun processFrame(
        buff: ByteArray,
        currentState: WheelState,
        config: DecoderConfig
    ): WheelState? {
        if (buff.size < 36) return null

        val veteranNegative = config.gotwayNegative

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
        val version = "${ver / 1000}.${(ver % 1000) / 100}.${ver % 100}".padStart(9, '0')
        val pedalsMode = ByteUtils.shortFromBytesBE(buff, 30)
        val pitchAngle = ByteUtils.signedShortFromBytesBE(buff, 32)
        val hwPwm = ByteUtils.shortFromBytesBE(buff, 34)

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

        var state = currentState.copy(
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
            tiltBackSpeed = speedTiltback / 10,
            pedalsMode = pedalsMode,
            version = version,
            model = getModelName(),
            wheelType = WheelType.VETERAN
        )

        // Merge sub-type settings data
        if (subData != null) {
            state = state.copy(
                roll = subData.roll ?: state.roll,
                lockState = subData.lockState ?: state.lockState,
                highSpeedMode = subData.highSpeedMode ?: state.highSpeedMode,
                lowVoltageMode = subData.lowVoltageMode ?: state.lowVoltageMode,
                speakerVolume = subData.speakerVolume ?: state.speakerVolume,
                transportMode = subData.transportMode ?: state.transportMode,
                keyTone = subData.keyTone ?: state.keyTone,
                pedalSensitivity = subData.pedalHardness ?: state.pedalSensitivity,
            )
        }

        return state
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
                // Battery % override at byte 50
                if (buff.size > 50) {
                    val pct = buff[50].toInt() and 0xFF
                    if (pct in 0..100) SubTypeData(batteryOverride = pct) else null
                } else null
            }
            8 -> parseControlSettings(buff)
            else -> null
        }
    }

    private fun parseControlSettings(buff: ByteArray): SubTypeData {
        // Control settings from sub-type 8
        // 0x80 (128) means "not supported" for each field
        val NOT_SUPPORTED = 0x80

        val pedalHardness = if (buff.size > 50) {
            val raw = buff[50].toInt() and 0xFF
            if (raw == NOT_SUPPORTED) null else raw
        } else null

        val transport = if (buff.size > 57) {
            val raw = buff[57].toInt() and 0xFF
            if (raw == NOT_SUPPORTED) null else raw != 0
        } else null

        val volume = if (buff.size > 59) {
            val raw = buff[59].toInt() // signed byte
            if ((raw.toInt() and 0xFF) == NOT_SUPPORTED) null else raw
        } else null

        val lowVol = if (buff.size > 60) {
            val raw = buff[60].toInt() and 0xFF
            if (raw == NOT_SUPPORTED) null else raw != 0
        } else null

        val highSpeed = if (buff.size > 61) {
            val raw = buff[61].toInt() and 0xFF
            if (raw == NOT_SUPPORTED) null else raw != 0
        } else null

        val keyToneVal = if (buff.size > 63) {
            val raw = buff[63].toInt() and 0xFF
            if (raw == NOT_SUPPORTED) null else raw
        } else null

        return SubTypeData(
            pedalHardness = pedalHardness,
            transportMode = transport,
            speakerVolume = volume,
            lowVoltageMode = lowVol,
            highSpeedMode = highSpeed,
            keyTone = keyToneVal,
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
            mVer == 5 || mVer == 6 || mVer == 42 -> { // Lynx, Sherman L, Nosfet Apex (151V)
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
            42 -> VeteranSocTables.LYNX_151V // Nosfet Apex (same 151.2V, same pack config)
            else -> null // Oryx, Nosfet Aero, unknown — use piecewise fallback
        }
    }

    private fun getCellsForWheel(): Int {
        return when {
            mVer == 4 || mVer == 7 || mVer == 43 -> 30
            mVer == 8 -> 42
            mVer >= 5 -> 36
            else -> 24
        }
    }

    private fun getModelName(): String {
        return when (mVer) {
            0, 1 -> "Sherman"
            2 -> "Abrams"
            3 -> "Sherman S"
            4 -> "Patton"
            5 -> "Lynx"
            6 -> "Sherman L"
            7 -> "Patton S"
            8 -> "Oryx"
            42 -> "Nosfet Apex"
            43 -> "Nosfet Aero"
            else -> "Unknown Veteran"
        }
    }

    override fun isReady(): Boolean = stateLock.withLock { mVer != 0 }

    override fun reset() {
        stateLock.withLock {
            unpacker.reset()
            lastPacketTime = 0
            mVer = 0
            bms1 = SmartBms()
            bms2 = SmartBms()
        }
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

    override fun buildCommand(command: WheelCommand): List<WheelCommand> {
        val ver = stateLock.withLock { mVer }
        return when (command) {
            is WheelCommand.Beep -> {
                if (ver < 3) {
                    listOf(WheelCommand.SendBytes("b".encodeToByteArray()))
                } else {
                    // cmd 0x0E, value at byte 9 = 0x01
                    listOf(WheelCommand.SendBytes(
                        buildVeteranCommand(0x0E, 9, 0x01)
                    ))
                }
            }
            is WheelCommand.SetLight -> {
                if (ver < 3) {
                    listOf(WheelCommand.SendBytes(
                        if (command.enabled) "SetLightON".encodeToByteArray()
                        else "SetLightOFF".encodeToByteArray()
                    ))
                } else {
                    // cmd 0x0D, value at byte 8 = 1/0
                    listOf(WheelCommand.SendBytes(
                        buildVeteranCommand(0x0D, 8, if (command.enabled) 1 else 0, byte5 = 0x01)
                    ))
                }
            }
            is WheelCommand.SetPedalsMode -> {
                if (ver < 3) {
                    val cmd = when (command.mode) {
                        0 -> "SETh"
                        1 -> "SETm"
                        2 -> "SETs"
                        else -> return emptyList()
                    }
                    listOf(WheelCommand.SendBytes(cmd.encodeToByteArray()))
                } else {
                    // cmd 0x0C, value at byte 7
                    // UI sends 0=hard, 1=medium, 2=soft
                    // Wheel expects 3=hard, 2=medium, 1=soft
                    val value = when (command.mode) {
                        0 -> 3  // hard
                        1 -> 2  // medium
                        2 -> 1  // soft
                        else -> return emptyList()
                    }
                    listOf(WheelCommand.SendBytes(
                        buildVeteranCommand(0x0C, 7, value, byte5 = 0x01)
                    ))
                }
            }
            is WheelCommand.SetAlarmSpeed -> {
                if (ver < 3) return emptyList()
                // cmd 0x11, value at byte 12 = speed + 10
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x11, 12, command.speed + 10, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetPedalTilt -> {
                if (ver < 3) return emptyList()
                // cmd 0x10, value at byte 11 = angle + 80
                // command.angle comes from UI as raw degrees (e.g. -8 to 8)
                // After dispatch: setPedalTilt multiplies by 10, so angle is degrees*10
                // Encoding: value = angle + 80 (where angle is degrees*10)
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x10, 11, command.angle + 80, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetTransportMode -> {
                if (ver < 3) return emptyList()
                // cmd 0x16, value at byte 17 = 0/1
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x16, 17, if (command.enabled) 1 else 0, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetSpeakerVolume -> {
                if (ver < 3) return emptyList()
                // cmd 0x18, value at byte 19
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x18, 19, command.volume, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetHighSpeedMode -> {
                if (ver < 3) return emptyList()
                // cmd 0x1A, value at byte 21 = 0/1
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x1A, 21, if (command.enabled) 1 else 0, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetLowVoltageMode -> {
                if (ver < 3) return emptyList()
                // cmd 0x19, value at byte 20 = 0/1
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x19, 20, if (command.enabled) 1 else 0, byte5 = 0x01)
                ))
            }
            is WheelCommand.SetKeyTone -> {
                if (ver < 3) return emptyList()
                // cmd 0x1C, value at byte 23
                listOf(WheelCommand.SendBytes(
                    buildVeteranCommand(0x1C, 23, command.value, byte5 = 0x01)
                ))
            }
            is WheelCommand.PowerOff -> {
                if (ver < 3) return emptyList()
                // cmd 0x16, byte 16 = 1 (close in 10 seconds)
                // This has a special layout: value is at byte 16, not the last byte
                // Use the CMD_SET_CLOSE_IN_10 pattern from decompiled app
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
            else -> emptyList()
        }
    }

    override fun getInitCommands(): List<WheelCommand> = emptyList()

    companion object {
        private const val WAITING_TIME = 100L
    }
}
