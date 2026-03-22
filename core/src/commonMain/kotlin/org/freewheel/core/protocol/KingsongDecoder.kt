package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.SmartBms
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.ByteUtils
import kotlin.math.roundToInt

/**
 * KingSong protocol decoder.
 *
 * KingSong uses fixed 20-byte frames with header AA 55.
 * Frame type is indicated by byte 16.
 *
 * Supports:
 * - KS-14, KS-16, KS-18 series
 * - KS-S18, KS-S16, KS-S19, KS-S20, KS-S22
 * - KS-F18P, KS-F22P
 *
 * Frame format: Fixed 20 bytes
 * - Bytes 0-1:  Header (AA 55)
 * - Bytes 2-15: Data payload (varies by frame type)
 * - Byte 16:    Frame type
 * - Byte 17:    0x14 (constant)
 * - Bytes 18-19: Footer (5A 5A)
 *
 * Frame types:
 *   0xA9 = Live telemetry    0xB9 = Distance/time
 *   0xBB = Name/type         0xB3 = Serial number
 *   0xF5 = CPU load/PWM      0xF6 = Speed limit
 *   0xA4/0xB5 = Alerts       0xF1/0xF2 = BMS data
 *   0xE1/0xE2 = BMS serial   0xE5/0xE6 = BMS firmware
 *   0xD0 = Extended BMS (F-series)
 *   0xA2 = Ride mode confirm  0xC9 = Battery temperature
 *   0x46 = Password login     0x4C = Lift sensor status
 *   0x55 = Headlight mode     0x4D = LED mode readback
 *   0x3F = Turn-off timer     0x5F = Lock status
 *
 * Thread-safe: All methods except [buildCommand] are called from the WCM
 * event loop (single-threaded). [buildCommand] receives an immutable
 * [DecoderState] snapshot, so no lock is needed.
 */
class KingsongDecoder : WheelDecoder {

    override val wheelType: WheelType = WheelType.KINGSONG

    // Mutable state — only accessed from the WCM event loop (single-threaded)
    private var ksAlarm1Speed = 0
    private var ksAlarm2Speed = 0
    private var ksAlarm3Speed = 0
    private var wheelMaxSpeed = 0
    private var m18Lkm = true
    private var mMode = 0
    private var mSpeedLimit = 0.0
    private var model = ""
    private var name = ""
    private var serialNumber = ""
    private var version = ""
    private var hasReceivedVoltage = false
    private var bms1 = SmartBms()
    private var bms2 = SmartBms()
    private var versionNum = 0           // firmware version number (e.g., 205 for v2.05)

    /** Result from a single Kingsong frame handler. */
    private data class KsFrameOutput(
        val telemetry: TelemetryState? = null,
        val identity: WheelIdentity? = null,
        val settings: WheelSettings.Kingsong? = null,
        val hasNewData: Boolean = false,
        val commands: List<WheelCommand> = emptyList()
    )

    override fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult {
        if (data.size < 20) return DecodeResult.Buffering

        // Check header (AA 55)
        val a1 = data[0].toInt() and 0xFF
        val a2 = data[1].toInt() and 0xFF
        if (a1 != 0xAA || a2 != 0x55) return DecodeResult.Buffering

        val frameType = data[16].toInt() and 0xFF

        val tel = currentState.telemetry
        val id = currentState.identity
        val ks = currentState.settings as? WheelSettings.Kingsong ?: WheelSettings.Kingsong()

        val output: KsFrameOutput? = when (frameType) {
            FrameType.LIVE_DATA -> processLiveData(data, tel, id, config)
            FrameType.DISTANCE_TIME -> processDistanceTimeData(data, tel, ks)
            FrameType.NAME_TYPE -> processNameTypeData(data, id)
            FrameType.SERIAL_NUMBER -> processSerialNumber(data, id)
            FrameType.CPU_LOAD_PWM -> processCpuLoadPwm(data, tel)
            FrameType.SPEED_LIMIT -> processSpeedLimit(data, tel)
            FrameType.MAX_SPEED_ALERTS, FrameType.MAX_SPEED_ALERTS_2 ->
                processMaxSpeedAlerts(data, ks, frameType)
            FrameType.BMS_DATA_1, FrameType.BMS_DATA_2 -> {
                processBmsData(data, frameType)
                KsFrameOutput()
            }
            FrameType.BMS_SERIAL_1, FrameType.BMS_SERIAL_2 -> {
                processBmsSerial(data, frameType)
                null
            }
            FrameType.BMS_FW_1, FrameType.BMS_FW_2 -> {
                processBmsFirmware(data, frameType)
                null
            }
            FrameType.LOCK_STATUS -> processLockStatus(data, ks)
            FrameType.LOCK_RESULT -> processLockResult(data, ks)
            FrameType.RIDE_MODE_CONFIRM -> processRideModeConfirm(data, ks)
            FrameType.BATTERY_TEMP -> processBatteryTemp(data, tel)
            FrameType.PASSWORD_LOGIN -> processPasswordLogin(data, ks)
            FrameType.LIFT_SENSOR -> processLiftSensor(data, ks)
            FrameType.HEADLIGHT_MODE -> processHeadlightMode(data, ks)
            FrameType.LED_MODE_READBACK -> processLedModeReadback(data, ks)
            FrameType.TURN_OFF_TIMER -> processTurnOffTimer(data, ks)
            else -> null
        }

        val typeName = FRAME_TYPE_NAMES[frameType] ?: "0x${frameType.toString(16)}"

        return if (output != null) {
            val bmsSnapshot = BmsState(bms1 = bms1.toSnapshot(), bms2 = bms2.toSnapshot())

            // Ensure wheelType is KINGSONG
            val resolvedIdentity = when {
                output.identity != null && output.identity.wheelType == WheelType.Unknown ->
                    output.identity.copy(wheelType = WheelType.KINGSONG)
                output.identity != null -> output.identity
                id.wheelType == WheelType.Unknown -> id.copy(wheelType = WheelType.KINGSONG)
                else -> null
            }

            DecodeResult.Success(DecodedData(
                telemetry = output.telemetry?.takeIf { it != tel },
                identity = resolvedIdentity?.takeIf { it != id },
                bms = bmsSnapshot.takeIf { it != currentState.bms },
                settings = output.settings?.takeIf { it != currentState.settings },
                commands = output.commands,
                hasNewData = output.hasNewData,
                frameTypes = listOf(typeName)
            ))
        } else {
            DecodeResult.Unhandled(
                reason = UnhandledReason.UnknownCommand(frameHex = "0x${frameType.toString(16)}"),
                frameData = data.copyOf()
            )
        }
    }

    /**
     * Frame 0xA9: Live telemetry data
     */
    private fun processLiveData(
        data: ByteArray,
        tel: TelemetryState,
        id: WheelIdentity,
        config: DecoderConfig
    ): KsFrameOutput {
        val voltage = ByteUtils.getInt2R(data, 2)
        if (voltage > 0) hasReceivedVoltage = true
        val speed = ByteUtils.getInt2R(data, 4)
        var totalDistance = ByteUtils.getInt4R(data, 6).toLong()

        // KS-18L distance scaling
        if (model == "KS-18L" && !m18Lkm) {
            totalDistance = (totalDistance * KS18L_SCALER).roundToInt().toLong()
        }

        val current = (data[10].toInt() and 0xFF) + (data[11].toInt() shl 8)
        val temperature = ByteUtils.getInt2R(data, 12)

        // Mode info
        if ((data[15].toInt() and 0xFF) == 224) {
            mMode = data[14].toInt()
        }

        // Calculate battery percentage
        val battery = calculateBatteryPercent(voltage, config.useCustomPercents)

        // Calculate power
        val power = ((current / 100.0) * voltage).roundToInt()

        return KsFrameOutput(
            telemetry = tel.copy(
                speed = speed, voltage = voltage, current = current,
                power = power, temperature = temperature,
                totalDistance = totalDistance, batteryLevel = battery
            ),
            identity = id.copy(
                modeStr = mMode.toString(), model = model, name = name,
                serialNumber = serialNumber, version = version,
                wheelType = WheelType.KINGSONG
            ),
            hasNewData = true
        )
    }

    /**
     * Frame 0xB9: Distance/Time/Fan data
     *
     * Bytes 2-5:   wheel distance (4-byte LE-pairs)
     * Bytes 6-7:   ride time (seconds)
     * Bytes 8-9:   top speed this ride
     * Byte 10:     light mode (0x12=off, 0x13=on, 0x14=auto)
     * Byte 11:     voice off flag (1=muted, 0=not muted)
     * Byte 12:     fan status
     * Byte 13:     charging status
     * Bytes 14-15: temperature2
     */
    private fun processDistanceTimeData(
        data: ByteArray,
        tel: TelemetryState,
        ks: WheelSettings.Kingsong
    ): KsFrameOutput {
        val distance = ByteUtils.getInt4R(data, 2).toLong()
        val rideTime = ByteUtils.getInt2R(data, 6)
        val topSpeed = ByteUtils.getInt2R(data, 8)
        val rawLightMode = data[10].toInt() and 0xFF
        val voiceOff = (data[11].toInt() and 0xFF) == 1
        val fanStatus = data[12].toInt()
        val chargingStatus = data[13].toInt()
        val temperature2 = ByteUtils.getInt2R(data, 14)

        // Normalize: 0x12=off(0), 0x13=on(1), 0x14=auto(2)
        val lightMode = if (rawLightMode in 0x12..0x14) rawLightMode - 0x12 else -1

        return KsFrameOutput(
            telemetry = tel.copy(
                wheelDistance = distance, rideTime = rideTime, topSpeed = topSpeed,
                fanStatus = fanStatus, chargingStatus = chargingStatus,
                temperature2 = temperature2
            ),
            settings = ks.copy(lightMode = lightMode, mute = voiceOff)
        )
    }

    /**
     * Frame 0xBB: Name and Type data
     *
     * For firmware >= 1.17, bytes 18-19 contain a checksum of the name bytes.
     * On mismatch, re-request the name.
     */
    private fun processNameTypeData(
        data: ByteArray,
        id: WheelIdentity
    ): KsFrameOutput {
        var end = 0
        var i = 0
        while (i < 14 && data[i + 2].toInt() != 0) {
            end++
            i++
        }

        name = data.decodeToString(2, 2 + end).trim()

        // Extract model from name (e.g., "KS-16X-1234" -> "KS-16X")
        val parts = name.split("-")
        model = if (parts.size > 1) {
            parts.dropLast(1).joinToString("-")
        } else {
            name
        }

        // Extract version from last part
        if (parts.size > 1) {
            try {
                versionNum = parts.last().toInt()
                val major = versionNum / 100
                val minor = versionNum % 100
                version = "$major.${minor.toString().padStart(2, '0')}"
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }

        val commands = mutableListOf<WheelCommand>()

        // Checksum validation for firmware >= 1.17
        if (versionNum >= 117) {
            var nameSum = 0
            for (j in 2 until 2 + end) {
                nameSum += data[j].toInt() and 0xFF
            }
            val checksum = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
            if (checksum != nameSum) {
                commands.add(WheelCommand.SendBytes(createRequest(InitCmd.REQUEST_NAME)))
            }
        }

        return KsFrameOutput(
            identity = id.copy(name = name, model = model, version = version),
            commands = commands
        )
    }

    /**
     * Frame 0xB3: Serial Number
     */
    private fun processSerialNumber(
        data: ByteArray,
        id: WheelIdentity
    ): KsFrameOutput {
        val snData = ByteArray(18)
        data.copyInto(snData, 0, 2, 16)
        data.copyInto(snData, 14, 17, 20)
        snData[17] = 0

        serialNumber = snData.decodeToString().trim { it <= ' ' || it == '\u0000' }

        return KsFrameOutput(
            identity = id.copy(serialNumber = serialNumber),
            commands = listOf(WheelCommand.SendBytes(createRequest(InitCmd.REQUEST_ALARMS)))
        )
    }

    /**
     * Frame 0xF5: CPU load, PWM, and hardware diagnostics
     *
     * Bytes 2-9 contain hardware fault indicators (non-zero = fault active).
     * Bytes 14-15 contain CPU load and PWM as before.
     */
    private fun processCpuLoadPwm(data: ByteArray, tel: TelemetryState): KsFrameOutput {
        // Hardware faults from bytes 2-9
        var faults = 0
        if (ByteUtils.getInt2R(data, 2) != 0) faults = faults or HwFault.CURRENT_AMPLITUDE
        if (ByteUtils.getInt2R(data, 4) != 0) faults = faults or HwFault.TEMPERATURE
        if ((data[6].toInt() and 0xFF) != 0) faults = faults or HwFault.MOTOR_PHASE_SHORT
        if ((data[7].toInt() and 0xFF) != 0) faults = faults or HwFault.GYROSCOPE_ERROR
        if ((data[8].toInt() and 0xFF) != 0) faults = faults or HwFault.MOTOR_HALL_ERROR
        if ((data[9].toInt() and 0xFF) != 0) faults = faults or HwFault.SN_BOARD_ERROR

        val alert = if (faults != 0) {
            buildList {
                if (faults and HwFault.CURRENT_AMPLITUDE != 0) add("Current amplitude fault")
                if (faults and HwFault.TEMPERATURE != 0) add("Temperature fault")
                if (faults and HwFault.MOTOR_PHASE_SHORT != 0) add("Motor phase short")
                if (faults and HwFault.GYROSCOPE_ERROR != 0) add("Gyroscope error")
                if (faults and HwFault.MOTOR_HALL_ERROR != 0) add("Motor hall error")
                if (faults and HwFault.SN_BOARD_ERROR != 0) add("SN board error")
            }.joinToString(", ")
        } else ""

        // Existing CPU load + PWM at bytes 14-15
        val cpuLoad = data[14].toInt()
        val output = (data[15].toInt() and 0xFF) * 100
        val calculatedPwm = output / 10000.0

        return KsFrameOutput(
            telemetry = tel.copy(
                hwFaults = faults, alert = alert, cpuLoad = cpuLoad,
                output = output, calculatedPwm = calculatedPwm
            )
        )
    }

    /**
     * Frame 0xF6: Speed limit, BMS SOC, energy consumption, total on-time, fault code
     *
     * - Bytes 2-3: Speed limit
     * - Byte 4: BMS state of charge (raw 1-101 → 0-100%)
     * - Bytes 6-9: Total energy consumption in Wh (LE 32-bit)
     * - Bytes 12-13: Total power-on time in seconds
     * - Bytes 14-15: Fault code
     */
    private fun processSpeedLimit(data: ByteArray, tel: TelemetryState): KsFrameOutput {
        mSpeedLimit = ByteUtils.getInt2R(data, 2) / 100.0

        // BMS SOC raw range is 1-101, maps to 0-100%. 0 or >101 = unknown.
        val raw = data[4].toInt() and 0xFF
        val bmsSoc = if (raw in 1..101) raw - 1 else -1

        val totalEnergyWh = ByteUtils.intFromBytesLE(data, 6).toLong()
        val totalOnTime = ByteUtils.getInt2R(data, 12)
        val faultCode = ByteUtils.getInt2R(data, 14)
        val error = if (faultCode != 0) "Fault code: $faultCode" else ""

        return KsFrameOutput(
            telemetry = tel.copy(
                speedLimit = mSpeedLimit, bmsSoc = bmsSoc,
                totalEnergyWh = totalEnergyWh, totalOnTime = totalOnTime,
                faultCode = faultCode, error = error
            )
        )
    }

    /**
     * Frame 0xA4/0xB5: Max speed and alerts
     *
     * Uses 16-bit reads for alarm speeds — backward-compatible since
     * the high byte was 0 on older wheels.
     */
    private fun processMaxSpeedAlerts(
        data: ByteArray,
        ks: WheelSettings.Kingsong,
        frameType: Int
    ): KsFrameOutput {
        wheelMaxSpeed = ByteUtils.getInt2R(data, 10)
        ksAlarm3Speed = ByteUtils.getInt2R(data, 8)
        ksAlarm2Speed = ByteUtils.getInt2R(data, 6)
        ksAlarm1Speed = ByteUtils.getInt2R(data, 4)

        val commands = mutableListOf<WheelCommand>()

        // Respond to 0xA4 with alarm settings request
        if (frameType == FrameType.MAX_SPEED_ALERTS) {
            val response = data.copyOf()
            response[16] = InitCmd.REQUEST_ALARMS.toByte()
            commands.add(WheelCommand.SendBytes(response))
        }

        return KsFrameOutput(
            settings = ks.copy(
                ksAlarm1Speed = ksAlarm1Speed, ksAlarm2Speed = ksAlarm2Speed,
                ksAlarm3Speed = ksAlarm3Speed, ksTiltbackSpeed = wheelMaxSpeed
            ),
            hasNewData = true,
            commands = commands
        )
    }

    /**
     * Frame 0xF1/0xF2: BMS data
     *
     * Modifies internal SmartBms objects. BMS snapshots are applied in decode()
     * via the BmsState comparison at the end.
     */
    private fun processBmsData(data: ByteArray, frameType: Int) {
        val bmsNum = (frameType and 0xFF) - 0xF0
        val bms = if (bmsNum == 1) bms1 else bms2
        val pNum = data[17].toInt() and 0xFF

        when (pNum) {
            0x00 -> {
                bms.voltage = ByteUtils.getInt2R(data, 2) / 100.0
                bms.current = ByteUtils.getInt2R(data, 4) / 100.0
                bms.remCap = ByteUtils.getInt2R(data, 6) * 10
                bms.factoryCap = ByteUtils.getInt2R(data, 8) * 10
                bms.fullCycles = ByteUtils.getInt2R(data, 10)
                bms.remPerc = if (bms.factoryCap > 0) {
                    (bms.remCap / (bms.factoryCap / 100.0)).roundToInt()
                } else 0
            }
            0x01 -> {
                bms.temp1 = (ByteUtils.getInt2R(data, 2) - 2730) / 10.0
                bms.temp2 = (ByteUtils.getInt2R(data, 4) - 2730) / 10.0
                bms.temp3 = (ByteUtils.getInt2R(data, 6) - 2730) / 10.0
                bms.temp4 = (ByteUtils.getInt2R(data, 8) - 2730) / 10.0
                bms.temp5 = (ByteUtils.getInt2R(data, 10) - 2730) / 10.0
                bms.temp6 = (ByteUtils.getInt2R(data, 12) - 2730) / 10.0
                bms.tempMos = (ByteUtils.getInt2R(data, 14) - 2730) / 10.0
            }
            0x02, 0x03, 0x04, 0x05 -> {
                // Cell voltages (7 cells per packet)
                val startCell = (pNum - 2) * 7
                for (i in 0 until 7) {
                    val cellIndex = startCell + i
                    if (cellIndex < bms.cells.size) {
                        bms.cells[cellIndex] = ByteUtils.getInt2R(data, 2 + i * 2) / 1000.0
                    }
                }
            }
            0x06 -> {
                // Last cells and MOS environment temp
                bms.cells[28] = ByteUtils.getInt2R(data, 2) / 1000.0
                bms.cells[29] = ByteUtils.getInt2R(data, 4) / 1000.0
                bms.tempMosEnv = (ByteUtils.getInt2R(data, 10) - 2730) / 10.0

                // Calculate cell statistics
                updateBmsCellStats(bms)
            }
            0xD0 -> {
                // Extended BMS packet for F-series
                processExtendedBmsPacket(data, bms)
            }
        }
    }

    private fun processExtendedBmsPacket(data: ByteArray, bms: SmartBms) {
        if (data.size < 23) return  // Minimum: 21 (cell count) + 2 bytes
        val cells = (data[21].toInt() and 0xFF).coerceAtMost(SmartBms.MAX_CELLS)

        // Read cell voltages — bounds-check both source (data) and destination (bms.cells)
        val cellDataEnd = 22 + cells * 2
        if (data.size < cellDataEnd + 1) return
        for (i in 0 until cells) {
            bms.cells[i] = ByteUtils.getInt2R(data, 22 + i * 2) / 1000.0
        }

        val offset = 23 + cells * 2
        if (data.size < offset + 16) return  // Need at least 8 temp values (16 bytes)
        val nTemp = data[offset - 1].toInt() and 0xFF

        // Read temperatures
        bms.temp1 = (ByteUtils.getInt2R(data, offset) - 2730) / 10.0
        bms.temp2 = (ByteUtils.getInt2R(data, offset + 2) - 2730) / 10.0
        bms.temp3 = (ByteUtils.getInt2R(data, offset + 4) - 2730) / 10.0
        bms.temp4 = (ByteUtils.getInt2R(data, offset + 6) - 2730) / 10.0
        bms.temp5 = (ByteUtils.getInt2R(data, offset + 8) - 2730) / 10.0
        bms.temp6 = (ByteUtils.getInt2R(data, offset + 10) - 2730) / 10.0
        bms.tempMos = (ByteUtils.getInt2R(data, offset + 12) - 2730) / 10.0
        bms.tempMosEnv = (ByteUtils.getInt2R(data, offset + 14) - 2730) / 10.0

        val offset2 = offset + nTemp * 2
        if (data.size < offset2 + 22) return  // Need remaining fields (22 bytes)
        bms.current = ByteUtils.getInt2R(data, offset2) / 100.0
        bms.voltage = ByteUtils.getInt2R(data, offset2 + 2) / 100.0
        bms.remPerc = ByteUtils.getInt2R(data, offset2 + 4) / 10
        bms.fullCycles = ByteUtils.getInt2R(data, offset2 + 9)
        bms.factoryCap = ByteUtils.getInt2R(data, offset2 + 11) * 10
        bms.remCap = bms.remPerc * bms.factoryCap / 100
        bms.temp1Env = ByteUtils.getInt2R(data, offset2 + 14) / 10.0
        bms.temp2Env = ByteUtils.getInt2R(data, offset2 + 16) / 10.0
        bms.humidity1Env = ByteUtils.getInt2R(data, offset2 + 18) / 10.0
        bms.humidity2Env = ByteUtils.getInt2R(data, offset2 + 20) / 10.0

        // Calculate cell statistics
        updateBmsCellStats(bms, cells)
    }

    /**
     * Frame 0xE1/0xE2: BMS Serial Number
     *
     * Modifies internal SmartBms serial; returns no state update (Unhandled path).
     */
    private fun processBmsSerial(data: ByteArray, frameType: Int) {
        val bmsNum = (frameType and 0xFF) - 0xE0
        val bms = if (bmsNum == 1) bms1 else bms2

        val snData = ByteArray(18)
        data.copyInto(snData, 0, 2, 16)
        data.copyInto(snData, 14, 17, 20)
        snData[17] = 0

        bms.serialNumber = snData.decodeToString().trim { it <= ' ' || it == '\u0000' }
    }

    /**
     * Frame 0xE5/0xE6: BMS Firmware Version
     *
     * Modifies internal SmartBms firmware; returns no state update (Unhandled path).
     */
    private fun processBmsFirmware(data: ByteArray, frameType: Int) {
        val bmsNum = (frameType and 0xFF) - 0xE4
        val bms = if (bmsNum == 1) bms1 else bms2

        val snData = ByteArray(19)
        data.copyInto(snData, 0, 2, 16)
        data.copyInto(snData, 14, 17, 20)
        snData[18] = 0

        bms.versionNumber = snData.decodeToString().trim { it <= ' ' || it == '\u0000' }
    }

    /**
     * Frame 0x5F: Lock status report
     * data[2] == 1 or 2 means locked, otherwise unlocked
     */
    private fun processLockStatus(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val lockByte = data[2].toInt() and 0xFF
        val locked = lockByte == 1 || lockByte == 2
        return KsFrameOutput(settings = ks.copy(lockState = if (locked) 1 else 0))
    }

    /**
     * Frame 0xB1: Lock/unlock command result
     * data[2]==1 and data[3]==0 means lock success
     */
    private fun processLockResult(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val cmd = data[2].toInt() and 0xFF
        val result = data[3].toInt() and 0xFF
        return if (cmd == 1 && result == 0) {
            KsFrameOutput(settings = ks.copy(lockState = 1))
        } else {
            KsFrameOutput(settings = ks.copy(lockState = 0))
        }
    }

    // ==================== New Frame Type Handlers ====================

    /**
     * Frame 0xA2: Ride mode change confirmation
     * byte[2]=1,byte[3]=0 → success. byte[4] = new mode (0=play,1=ride,2=study).
     */
    private fun processRideModeConfirm(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val success = (data[2].toInt() and 0xFF) == 1 && (data[3].toInt() and 0xFF) == 0
        if (!success) return KsFrameOutput()
        val newMode = data[4].toInt() and 0xFF
        return KsFrameOutput(settings = ks.copy(pedalsMode = newMode))
    }

    /**
     * Frame 0xC9: Battery temperature
     * bytes[4,5] = temperature via getInt2R → temperature2
     * byte[15] bit4 = charge flag → chargingStatus
     */
    private fun processBatteryTemp(data: ByteArray, tel: TelemetryState): KsFrameOutput {
        val batteryTemp = ByteUtils.getInt2R(data, 4)
        val charging = if ((data[15].toInt() and 0x10) != 0) 1 else 0
        return KsFrameOutput(
            telemetry = tel.copy(temperature2 = batteryTemp, chargingStatus = charging)
        )
    }

    /**
     * Frame 0x46: Password login result
     * byte[2]=1 → need password (wheel is locked). byte[2]=0 → logged in.
     */
    private fun processPasswordLogin(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val needPassword = (data[2].toInt() and 0xFF) == 1
        return KsFrameOutput(settings = ks.copy(lockState = if (needPassword) 1 else 0))
    }

    /**
     * Frame 0x4C: Lift sensor status
     * byte[2]=1 → on.
     */
    private fun processLiftSensor(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val enabled = (data[2].toInt() and 0xFF) == 1
        return KsFrameOutput(settings = ks.copy(handleButton = enabled))
    }

    /**
     * Frame 0x55: Headlight mode readback
     * byte[2]=0 → normal, byte[2]=1 → strobe.
     */
    private fun processHeadlightMode(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val strobe = (data[2].toInt() and 0xFF) == 1
        // Map: 0=normal (on), 1=strobe → lightMode: 1=on, 2=strobe
        return KsFrameOutput(settings = ks.copy(lightMode = if (strobe) 2 else 1))
    }

    /**
     * Frame 0x4D: LED mode readback
     * byte[2] = mode value.
     */
    private fun processLedModeReadback(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        val mode = data[2].toInt() and 0xFF
        return KsFrameOutput(settings = ks.copy(ledMode = mode))
    }

    /**
     * Frame 0x3F: Turn-off timer
     * byte[2]=0 → bytes[4,5] = timer value in minutes.
     */
    private fun processTurnOffTimer(data: ByteArray, ks: WheelSettings.Kingsong): KsFrameOutput {
        if ((data[2].toInt() and 0xFF) != 0) return KsFrameOutput()
        val timerMinutes = ByteUtils.getInt2R(data, 4)
        return KsFrameOutput(settings = ks.copy(autoOffTime = timerMinutes * 60))
    }

    private fun updateBmsCellStats(bms: SmartBms, cellCount: Int = getCellsForWheel()) {
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
        bms.avgCell = totalVolt / cellCount
    }

    private fun calculateBatteryPercent(voltage: Int, useBetterPercents: Boolean): Int {
        return when {
            is84vWheel() -> calc84vBattery(voltage, useBetterPercents)
            is126vWheel() -> calc126vBattery(voltage, useBetterPercents)
            is151vWheel() -> calc151vBattery(voltage, useBetterPercents)
            is176vWheel() -> calc176vBattery(voltage, useBetterPercents)
            is100vWheel() -> calc100vBattery(voltage, useBetterPercents)
            else -> calc67vBattery(voltage, useBetterPercents)
        }
    }

    private fun calc84vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 8350 -> 100
                voltage > 6800 -> (voltage - 6650) / 17
                voltage > 6400 -> (voltage - 6400) / 45
                else -> 0
            }
        } else {
            when {
                voltage < 6250 -> 0
                voltage >= 8250 -> 100
                else -> (voltage - 6250) / 20
            }
        }
    }

    private fun calc126vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 12525 -> 100
                voltage > 10200 -> ((voltage - 9975) / 25.5).roundToInt()
                voltage > 9600 -> ((voltage - 9600) / 67.5).roundToInt()
                else -> 0
            }
        } else {
            when {
                voltage < 9375 -> 0
                voltage >= 12375 -> 100
                else -> (voltage - 9375) / 30
            }
        }
    }

    private fun calc151vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 15030 -> 100
                voltage > 12240 -> ((voltage - 11970) / 30.6).roundToInt()
                voltage > 11520 -> ((voltage - 11520) / 81.0).roundToInt()
                else -> 0
            }
        } else {
            when {
                voltage < 11250 -> 0
                voltage >= 14850 -> 100
                else -> (voltage - 11250) / 36
            }
        }
    }

    private fun calc176vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 17535 -> 100
                voltage > 14280 -> ((voltage - 13965) / 35.7).roundToInt()
                voltage > 13440 -> ((voltage - 13440) / 94.5).roundToInt()
                else -> 0
            }
        } else {
            when {
                voltage < 13125 -> 0
                voltage >= 17325 -> 100
                else -> (voltage - 13125) / 42
            }
        }
    }

    private fun calc100vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 10020 -> 100
                voltage > 8160 -> ((voltage - 7980) / 20.4).roundToInt()
                voltage > 7680 -> ((voltage - 7680) / 54.0).roundToInt()
                else -> 0
            }
        } else {
            when {
                voltage < 7500 -> 0
                voltage >= 9900 -> 100
                else -> (voltage - 7500) / 24
            }
        }
    }

    private fun calc67vBattery(voltage: Int, useBetterPercents: Boolean): Int {
        return if (useBetterPercents) {
            when {
                voltage > 6680 -> 100
                voltage > 5440 -> ((voltage - 5320) / 13.6).roundToInt()
                voltage > 5120 -> (voltage - 5120) / 36
                else -> 0
            }
        } else {
            when {
                voltage < 5000 -> 0
                voltage >= 6600 -> 100
                else -> (voltage - 5000) / 16
            }
        }
    }

    private fun is84vWheel(): Boolean {
        return model in listOf("KS-18L", "KS-16X", "KS-16XF", "RW", "KS-18LH", "KS-18LY", "KS-S18", "KS-S16", "KS-S16P") ||
                name.startsWith("ROCKW")
    }

    private fun is126vWheel(): Boolean = model in listOf("KS-S20", "KS-S22")
    private fun is151vWheel(): Boolean = model in listOf("KS-F18P")
    private fun is176vWheel(): Boolean = model in listOf("KS-F22P")
    private fun is100vWheel(): Boolean = model in listOf("KS-S19")

    private fun getCellsForWheel(): Int {
        return when {
            is84vWheel() -> 20
            is100vWheel() -> 24
            is126vWheel() -> 30
            is151vWheel() -> 36
            is176vWheel() -> 42
            else -> 16
        }
    }

    private fun createRequest(type: Int): ByteArray {
        return byteArrayOf(
            0xAA.toByte(), 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            type.toByte(), 0x14, 0x5A, 0x5A
        )
    }

    override val keepAliveIntervalMs: Long = 2500L

    override fun getKeepAliveCommand(): WheelCommand =
        WheelCommand.SendBytes(createRequest(CmdByte.KEEP_ALIVE))

    override fun isReady(): Boolean =
        model.isNotEmpty() && hasReceivedVoltage

    override fun getCapabilities(): CapabilitySet {
        if (model.isEmpty()) return CapabilitySet()
        return CapabilitySet(
            supportedCommands = SUPPORTED_COMMANDS,
            detectedModel = model,
            firmwareVersion = version,
            isResolved = true
        )
    }

    override fun reset() {
        ksAlarm1Speed = 0
        ksAlarm2Speed = 0
        ksAlarm3Speed = 0
        wheelMaxSpeed = 0
        m18Lkm = true
        mMode = 0
        mSpeedLimit = 0.0
        model = ""
        name = ""
        serialNumber = ""
        version = ""
        hasReceivedVoltage = false
        bms1 = SmartBms()
        bms2 = SmartBms()
        versionNum = 0
    }

    override fun buildCommand(command: WheelCommand, state: DecoderState?): List<WheelCommand> {
        return when (command) {
            is WheelCommand.Beep -> {
                listOf(WheelCommand.SendBytes(createRequest(CmdByte.BEEP)))
            }
            is WheelCommand.SetLight -> {
                // SetLight(true) = light on = mode 1, SetLight(false) = light off = mode 0
                val mode = if (command.enabled) 1 else 0
                buildCommand(WheelCommand.SetLightMode(mode))
            }
            is WheelCommand.SetLightMode -> {
                // mode: 0=off(0x12), 1=on(0x13), 2=auto(0x14)
                val data = getEmptyRequest()
                data[2] = (command.mode + 0x12).toByte()
                data[3] = 0x00  // preserve voice on; don't clobber
                data[16] = CmdByte.LIGHT_MODE.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetPedalsMode -> {
                val data = getEmptyRequest()
                data[2] = command.mode.toByte()
                data[3] = 0xE0.toByte()
                data[16] = CmdByte.PEDALS_MODE.toByte()
                data[17] = 0x15
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.Calibrate -> {
                listOf(WheelCommand.SendBytes(createRequest(CmdByte.CALIBRATE)))
            }
            is WheelCommand.PowerOff -> {
                listOf(WheelCommand.SendBytes(createRequest(CmdByte.POWER_OFF)))
            }
            is WheelCommand.SetLed -> {
                // Color LED on/off: 0x6C with byte[2]=0(on)/1(off) — inverted logic
                val data = getEmptyRequest()
                data[2] = if (command.enabled) 0x00 else 0x01
                data[16] = CmdByte.LED_ON_OFF.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetLedMode -> {
                // LED pattern mode: 0x4D with byte[2]=mode
                val data = getEmptyRequest()
                data[2] = command.mode.toByte()
                data[16] = CmdByte.LED_PATTERN.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetStrobeMode -> {
                val data = getEmptyRequest()
                data[2] = command.mode.toByte()
                data[16] = CmdByte.STROBE_MODE.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetKingsongAlarms -> {
                val data = getEmptyRequest()
                data[2] = command.alarm1Speed.toByte()
                data[4] = command.alarm2Speed.toByte()
                data[6] = command.alarm3Speed.toByte()
                data[8] = command.maxSpeed.toByte()
                data[16] = CmdByte.ALARM_SPEED.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.RequestAlarmSettings -> {
                listOf(WheelCommand.SendBytes(createRequest(InitCmd.REQUEST_ALARMS)))
            }
            is WheelCommand.SetLock -> {
                // KS lock uses password-based protocol (0x41/0x42) — not yet implemented
                emptyList()
            }
            is WheelCommand.SetMute -> {
                // Voice on/off via 0x73: byte[2]=light mode (preserve), byte[3]=mute flag
                // Light mode comes from the immutable state snapshot, not mutable decoder fields
                val ks = state?.settings as? WheelSettings.Kingsong
                val rawLightMode = when (ks?.lightMode) {
                    0 -> 0x12  // off
                    1 -> 0x13  // on
                    2 -> 0x14  // auto
                    else -> 0x13  // default: on (before first B9 frame)
                }
                val data = getEmptyRequest()
                data[2] = rawLightMode.toByte()
                data[3] = if (command.enabled) 0x01 else 0x00
                data[16] = CmdByte.LIGHT_MODE.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetHandleButton -> {
                // Lift sensor on/off via 0x7E
                val data = getEmptyRequest()
                data[2] = if (command.enabled) 0x01 else 0x00
                data[16] = CmdByte.LIFT_SENSOR.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.SetLightBrightness -> {
                // Display brightness via 0x54 (range 50-100)
                val data = getEmptyRequest()
                data[4] = command.value.coerceIn(50, 100).toByte()
                data[14] = 0x01
                data[15] = 0xF2.toByte()
                data[16] = CmdByte.INSTRUMENT_BRIGHTNESS.toByte()
                listOf(WheelCommand.SendBytes(data))
            }
            is WheelCommand.RequestBmsData -> {
                // bmsNum: 1 or 2, dataType: 0=serial, 1=moreData, 2=firmware
                // Maps to: E1(bms1 serial), E2(bms2 serial), E3(bms1 more), E4(bms2 more), E5(bms1 fw), E6(bms2 fw)
                val typeBase = when (command.dataType) {
                    0 -> 0xE1 // serial
                    1 -> 0xE3 // moreData
                    2 -> 0xE5 // firmware
                    else -> return emptyList()
                }
                val type = typeBase + (command.bmsNum - 1) // bms1=+0, bms2=+1
                val data = getEmptyRequest()
                data[16] = type.toByte()
                data[17] = 0x00
                data[18] = 0x00
                data[19] = 0x00
                listOf(WheelCommand.SendBytes(data))
            }
            else -> emptyList()
        }
    }

    private fun getEmptyRequest(): ByteArray {
        return byteArrayOf(
            0xAA.toByte(), 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x14, 0x5A, 0x5A
        )
    }

    override fun getInitCommands(): List<WheelCommand> {
        return listOf(
            WheelCommand.SendBytes(createRequest(InitCmd.REQUEST_NAME)),
            WheelCommand.SendDelayed(createRequest(InitCmd.REQUEST_SERIAL), 100),
            WheelCommand.SendDelayed(createRequest(InitCmd.REQUEST_ALARMS), 200),
            WheelCommand.SendDelayed(createRequest(InitCmd.REQUEST_LIGHT_STATUS), 300),
            WheelCommand.SendDelayed(createRequest(InitCmd.REQUEST_LIFT_SENSOR), 400)
        )
    }

    private object FrameType {
        const val LIVE_DATA = 0xA9
        const val DISTANCE_TIME = 0xB9
        const val NAME_TYPE = 0xBB
        const val SERIAL_NUMBER = 0xB3
        const val CPU_LOAD_PWM = 0xF5
        const val SPEED_LIMIT = 0xF6
        const val MAX_SPEED_ALERTS = 0xA4
        const val MAX_SPEED_ALERTS_2 = 0xB5
        const val BMS_DATA_1 = 0xF1
        const val BMS_DATA_2 = 0xF2
        const val BMS_SERIAL_1 = 0xE1
        const val BMS_SERIAL_2 = 0xE2
        const val BMS_FW_1 = 0xE5
        const val BMS_FW_2 = 0xE6
        const val LOCK_STATUS = 0x5F
        const val LOCK_RESULT = 0xB1
        const val RIDE_MODE_CONFIRM = 0xA2
        const val BATTERY_TEMP = 0xC9
        const val PASSWORD_LOGIN = 0x46
        const val LIFT_SENSOR = 0x4C
        const val HEADLIGHT_MODE = 0x55
        const val LED_MODE_READBACK = 0x4D
        const val TURN_OFF_TIMER = 0x3F
    }

    private val FRAME_TYPE_NAMES = mapOf(
        FrameType.LIVE_DATA to "LIVE_DATA",
        FrameType.DISTANCE_TIME to "DISTANCE_TIME",
        FrameType.NAME_TYPE to "NAME_TYPE",
        FrameType.SERIAL_NUMBER to "SERIAL_NUMBER",
        FrameType.CPU_LOAD_PWM to "CPU_LOAD_PWM",
        FrameType.SPEED_LIMIT to "SPEED_LIMIT",
        FrameType.MAX_SPEED_ALERTS to "MAX_SPEED_ALERTS",
        FrameType.MAX_SPEED_ALERTS_2 to "MAX_SPEED_ALERTS_2",
        FrameType.BMS_DATA_1 to "BMS_DATA_1",
        FrameType.BMS_DATA_2 to "BMS_DATA_2",
        FrameType.BMS_SERIAL_1 to "BMS_SERIAL_1",
        FrameType.BMS_SERIAL_2 to "BMS_SERIAL_2",
        FrameType.BMS_FW_1 to "BMS_FW_1",
        FrameType.BMS_FW_2 to "BMS_FW_2",
        FrameType.LOCK_STATUS to "LOCK_STATUS",
        FrameType.LOCK_RESULT to "LOCK_RESULT",
        FrameType.RIDE_MODE_CONFIRM to "RIDE_MODE_CONFIRM",
        FrameType.BATTERY_TEMP to "BATTERY_TEMP",
        FrameType.PASSWORD_LOGIN to "PASSWORD_LOGIN",
        FrameType.LIFT_SENSOR to "LIFT_SENSOR",
        FrameType.HEADLIGHT_MODE to "HEADLIGHT_MODE",
        FrameType.LED_MODE_READBACK to "LED_MODE_READBACK",
        FrameType.TURN_OFF_TIMER to "TURN_OFF_TIMER"
    )

    private object InitCmd {
        const val REQUEST_NAME = 0x9B
        const val REQUEST_SERIAL = 0x63
        const val REQUEST_ALARMS = 0x98
        const val REQUEST_LIGHT_STATUS = 0x5B
        const val REQUEST_LIFT_SENSOR = 0x81
    }

    private object CmdByte {
        const val BEEP = 0x88
        const val LIGHT_MODE = 0x73
        const val PEDALS_MODE = 0x87
        const val CALIBRATE = 0x89
        const val POWER_OFF = 0x40
        const val LED_ON_OFF = 0x6C
        const val LED_PATTERN = 0x4D
        const val STROBE_MODE = 0x53
        const val ALARM_SPEED = 0x85
        const val KEEP_ALIVE = 0x5E
        const val LIFT_SENSOR = 0x7E
        const val INSTRUMENT_BRIGHTNESS = 0x54
    }

    /** Hardware fault flag constants for the [hwFaults][TelemetryState.hwFaults] bitfield. */
    object HwFault {
        const val CURRENT_AMPLITUDE = 0x01
        const val TEMPERATURE = 0x02
        const val MOTOR_PHASE_SHORT = 0x04
        const val GYROSCOPE_ERROR = 0x08
        const val MOTOR_HALL_ERROR = 0x10
        const val SN_BOARD_ERROR = 0x20
    }

    companion object {
        private const val KS18L_SCALER = 0.83

        val SUPPORTED_COMMANDS: Set<SettingsCommandId> = setOf(
            SettingsCommandId.LIGHT_MODE,
            SettingsCommandId.LED,
            SettingsCommandId.LED_MODE,
            SettingsCommandId.STROBE_MODE,
            SettingsCommandId.PEDALS_MODE,
            SettingsCommandId.MUTE,
            SettingsCommandId.HANDLE_BUTTON,
            SettingsCommandId.LIGHT_BRIGHTNESS,
            SettingsCommandId.CALIBRATE,
            SettingsCommandId.POWER_OFF,
        )
    }
}
