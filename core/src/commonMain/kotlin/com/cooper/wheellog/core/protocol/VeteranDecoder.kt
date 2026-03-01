package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.SmartBms
import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.utils.ByteUtils
import com.cooper.wheellog.core.utils.Lock
import com.cooper.wheellog.core.utils.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

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
                        val calcCrc = crc32(data, 0, len)
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

    /**
     * Simple CRC32 calculation for KMP.
     */
    private fun crc32(data: ByteArray, offset: Int, length: Int): Long {
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

        return currentState.copy(
            speed = speed,
            voltage = voltage,
            phaseCurrent = phaseCurrent,
            current = current,
            power = power,
            temperature = temperature,
            wheelDistance = distance,
            totalDistance = totalDistance,
            batteryLevel = battery,
            chargingStatus = chargeMode,
            output = output,
            calculatedPwm = calculatedPwm,
            angle = pitchAngle / 100.0,
            version = version,
            model = getModelName(),
            wheelType = WheelType.VETERAN
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
        return when {
            mVer < 4 -> { // Sherman, Abrams, Sherman S (100V)
                if (useBetterPercents) {
                    when {
                        voltage > 10020 -> 100
                        voltage > 8160 -> ((voltage - 8070) / 19.5).roundToInt()
                        voltage > 7935 -> ((voltage - 7935) / 48.75).roundToInt()
                        else -> 0
                    }
                } else {
                    when {
                        voltage <= 7935 -> 0
                        voltage >= 9870 -> 100
                        else -> ((voltage - 7935) / 19.5).roundToInt()
                    }
                }
            }
            mVer == 4 || mVer == 7 || mVer == 43 -> { // Patton, Patton S, Nosfet Aero (126V)
                if (useBetterPercents) {
                    when {
                        voltage > 12525 -> 100
                        voltage > 10200 -> ((voltage - 9975) / 25.5).roundToInt()
                        voltage > 9600 -> ((voltage - 9600) / 67.5).roundToInt()
                        else -> 0
                    }
                } else {
                    when {
                        voltage <= 9918 -> 0
                        voltage >= 12337 -> 100
                        else -> ((voltage - 9918) / 24.2).roundToInt()
                    }
                }
            }
            mVer == 5 || mVer == 6 || mVer == 42 -> { // Lynx, Sherman L, Nosfet Apex (151V)
                if (useBetterPercents) {
                    when {
                        voltage > 15030 -> 100
                        voltage > 12240 -> ((voltage - 11970) / 30.6).roundToInt()
                        voltage > 11520 -> ((voltage - 11520) / 81.0).roundToInt()
                        else -> 0
                    }
                } else {
                    when {
                        voltage <= 11902 -> 0
                        voltage >= 14805 -> 100
                        else -> ((voltage - 11902) / 29.03).roundToInt()
                    }
                }
            }
            mVer == 8 -> { // Oryx (176V)
                if (useBetterPercents) {
                    when {
                        voltage > 17535 -> 100
                        voltage > 14280 -> ((voltage - 14123) / 34.125).roundToInt()
                        voltage > 13886 -> ((voltage - 13886) / 85.3125).roundToInt()
                        else -> 0
                    }
                } else {
                    when {
                        voltage <= 13886 -> 0
                        voltage >= 17272 -> 100
                        else -> ((voltage - 13886) / 34.125).roundToInt()
                    }
                }
            }
            else -> 1 // Unknown wheel, default to 1%
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

    override fun buildCommand(command: WheelCommand): List<WheelCommand> {
        return when (command) {
            is WheelCommand.Beep -> {
                val ver = stateLock.withLock { mVer }
                if (ver < 3) {
                    listOf(WheelCommand.SendBytes("b".encodeToByteArray()))
                } else {
                    listOf(WheelCommand.SendBytes(byteArrayOf(
                        0x4C, 0x6B, 0x41, 0x70, 0x0E, 0x00,
                        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
                        0xCA.toByte(), 0x87.toByte(), 0xE6.toByte(), 0x6F
                    )))
                }
            }
            is WheelCommand.SetLight -> listOf(
                WheelCommand.SendBytes(
                    if (command.enabled) "SetLightON".encodeToByteArray()
                    else "SetLightOFF".encodeToByteArray()
                )
            )
            is WheelCommand.SetPedalsMode -> {
                // 0=hard, 1=medium, 2=soft
                val cmd = when (command.mode) {
                    0 -> "SETh"
                    1 -> "SETm"
                    2 -> "SETs"
                    else -> return emptyList()
                }
                listOf(WheelCommand.SendBytes(cmd.encodeToByteArray()))
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

/**
 * Platform-specific current time function.
 */
expect fun currentTimeMillis(): Long
