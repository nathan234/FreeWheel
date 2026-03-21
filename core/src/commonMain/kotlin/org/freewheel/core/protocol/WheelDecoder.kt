package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType

/**
 * Lightweight decoder input holding the four domain sub-states.
 */
data class DecoderState(
    val telemetry: TelemetryState = TelemetryState(),
    val identity: WheelIdentity = WheelIdentity(),
    val bms: BmsState = BmsState(),
    val settings: WheelSettings = WheelSettings.None
)

/**
 * Interface for wheel protocol decoders.
 * Each EUC manufacturer has its own BLE protocol, and this interface
 * abstracts the decoding logic to enable cross-platform sharing.
 */
interface WheelDecoder {
    /**
     * The wheel type this decoder handles.
     */
    val wheelType: WheelType

    /**
     * Decode incoming BLE data and produce updated state.
     *
     * @param data Raw bytes received from the wheel
     * @param currentState The current decoder state (domain sub-states)
     * @param config Decoder configuration options
     * @return A [DecodeResult] indicating success, buffering, or unhandled frame
     */
    fun decode(data: ByteArray, currentState: DecoderState, config: DecoderConfig): DecodeResult

    /**
     * Check if the decoder has received enough data to be considered ready.
     * Some wheels require multiple data frames before all information is available.
     */
    fun isReady(): Boolean

    /**
     * Reset the decoder's internal state.
     * Called when disconnecting or switching wheels.
     */
    fun reset()

    /**
     * Get initialization commands to send after connecting.
     * Some wheels require specific commands to start sending telemetry.
     */
    fun getInitCommands(): List<WheelCommand> = emptyList()

    /**
     * Build protocol-specific byte commands for a high-level command.
     * Returns a list of SendBytes/SendDelayed commands, or empty list if unsupported.
     */
    fun buildCommand(command: WheelCommand, state: DecoderState? = null): List<WheelCommand> = emptyList()

    /**
     * Get keep-alive commands that should be sent periodically.
     * Returns null if no keep-alive is needed.
     */
    fun getKeepAliveCommand(): WheelCommand? = null

    /**
     * Interval in milliseconds for keep-alive commands.
     */
    val keepAliveIntervalMs: Long get() = 0L

    /**
     * Capabilities of the connected wheel.
     * Returns a default (unresolved) set until model/firmware are detected.
     * Decoders populate this from their [CapabilityMap] as identification frames arrive.
     */
    fun getCapabilities(): CapabilitySet = CapabilitySet()

    /**
     * Cumulative unpacker error statistics since last reset.
     * Returns null if the decoder doesn't use a separate unpacker.
     */
    fun getUnpackerStats(): UnpackerStats? = null
}

/**
 * Result from a [WheelDecoder.decode] call.
 *
 * Replaces the previous `DecodedData?` return type, which conflated three distinct states
 * (buffering, unhandled frame, success) into a single `null`.
 */
sealed class DecodeResult {
    /** Successfully decoded frame(s) into updated wheel state. */
    data class Success(val data: DecodedData) : DecodeResult()

    /** Unpacker hasn't assembled a complete frame yet — normal, not an error. */
    data object Buffering : DecodeResult()

    /** Got a complete frame but the decoder doesn't recognize it. */
    data class Unhandled(val reason: UnhandledReason, val frameData: ByteArray) : DecodeResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unhandled) return false
            return reason == other.reason && frameData.contentEquals(other.frameData)
        }
        override fun hashCode(): Int = 31 * reason.hashCode() + frameData.contentHashCode()
    }
}

/**
 * Structured classification for unhandled frames.
 *
 * Replaces free-form reason strings with categorized error classes,
 * enabling grouping and analysis (e.g., "400 unknown_command:0x15 frames").
 *
 * @param errorClass The category of unhandled frame.
 * @param detail Optional specifics (e.g., "0xa4" for the frame type byte).
 */
data class UnhandledReason(
    val errorClass: ErrorClass,
    val detail: String = ""
) {
    /**
     * Categories of unhandled frames, ordered by diagnostic signal.
     */
    enum class ErrorClass {
        /** Frame type / command byte not in decoder's table — protocol coverage gap. */
        UNKNOWN_COMMAND,

        /** Frame header doesn't match any known protocol (AutoDetect). */
        UNRECOGNIZED_HEADER,

        /** Checksum / XOR verification failed — BLE corruption or bad reassembly. */
        CHECKSUM_MISMATCH,

        /** Frame buffer shorter than expected — BLE truncation or unpacker issue. */
        FRAME_TOO_SHORT,

        /** Model / hardware ID not in decoder's table — new hardware variant. */
        UNKNOWN_MODEL,

        /** Escape sequence parsing failed (InMotion 0xAA / LeaperkimCan 0xA5). */
        ESCAPE_ERROR
    }

    override fun toString(): String = if (detail.isEmpty()) {
        errorClass.name.lowercase()
    } else {
        "${errorClass.name.lowercase()}:$detail"
    }
}

/**
 * Result from decoding wheel data.
 *
 * Decoders set domain pieces ([telemetry], [identity], [bms], [settings]);
 * the reducer merges only the pieces that changed.
 */
data class DecodedData(
    /** High-frequency telemetry update. */
    val telemetry: TelemetryState? = null,
    /** Wheel identity update (model, serial, version, etc.). */
    val identity: WheelIdentity? = null,
    /** BMS snapshot update. */
    val bms: BmsState? = null,
    /** Settings update. */
    val settings: WheelSettings? = null,

    /**
     * Commands to send back to the wheel (e.g., acknowledgments, requests).
     */
    val commands: List<WheelCommand> = emptyList(),

    /**
     * Flag indicating if this decode produced new telemetry data.
     */
    val hasNewData: Boolean = true,

    /**
     * Optional news/message from the wheel.
     */
    val news: String? = null,

    /**
     * Frame types decoded in this notification (e.g., "LIVE_DATA", "SETTINGS").
     * Populated by decoders to enable frame type distribution analysis.
     * May contain multiple entries when a single BLE notification contains multiple frames.
     */
    val frameTypes: List<String> = emptyList(),

    /**
     * Event log entries decoded from this frame (Veteran/Leaperkim).
     * Populated during event log download; empty during normal telemetry.
     */
    val logEntries: List<org.freewheel.core.domain.EventLogEntry> = emptyList()
)

/**
 * Configuration options for the decoder.
 */
data class DecoderConfig(
    /**
     * Use MPH instead of KM/H.
     */
    val useMph: Boolean = false,

    /**
     * Use Fahrenheit instead of Celsius.
     */
    val useFahrenheit: Boolean = false,

    /**
     * Use custom battery percentage calculation.
     */
    val useCustomPercents: Boolean = false,

    /**
     * Cell voltage considered empty (for custom percent calculation).
     */
    val cellVoltageTiltback: Int = 330,

    /**
     * Expected rotation speed at a given voltage (for PWM calculation).
     */
    val rotationSpeed: Int = 500,

    /**
     * Reference voltage for rotation speed (for PWM calculation).
     */
    val rotationVoltage: Int = 840,

    /**
     * Power factor for PWM calculation.
     */
    val powerFactor: Int = 100,

    /**
     * Battery capacity in Wh.
     */
    val batteryCapacity: Int = 0,

    /**
     * Password for InMotion wheels.
     */
    val wheelPassword: String = "",

    /**
     * Gotway speed/current polarity.
     * 0 = absolute value (always positive), default for most wheels.
     * 1 = keep original sign.
     * -1 = invert sign.
     */
    val gotwayNegative: Int = 0,

    /**
     * Apply 0.875 ratio to Gotway speed/distance values.
     * Some Gotway boards report inflated values that need scaling.
     */
    val useRatio: Boolean = false,

    /**
     * Gotway/Begode battery voltage configuration.
     * 0 = 67.2V (16S), 1 = 84V (20S), 2 = 100.8V (24S),
     * 3 = 126V (28S), 4 = 134.4V (32S), 5 = 168V (40S), 6 = 151V (36S).
     */
    val gotwayVoltage: Int = 0,

    /**
     * Whether to use hardware PWM from the wheel frame.
     * When false, PWM is calculated from speed/voltage/powerFactor formula.
     */
    val hwPwmEnabled: Boolean = true,

    /**
     * Whether to automatically use BMS voltage from frame 0x01 (Gotway).
     * When true, frame 0x01 voltage overrides frame 0x00 voltage, and
     * frame 0x00 voltage is suppressed after the first frame 0x01.
     * When false, frame 0x00 voltage is always used.
     */
    val autoVoltage: Boolean = true
)

/**
 * Commands that can be sent to the wheel.
 */
sealed class WheelCommand {
    /**
     * Send raw bytes to the wheel immediately.
     */
    data class SendBytes(val data: ByteArray) : WheelCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendBytes) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    /**
     * Send bytes after a delay.
     */
    data class SendDelayed(val data: ByteArray, val delayMs: Long) : WheelCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendDelayed) return false
            return data.contentEquals(other.data) && delayMs == other.delayMs
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + delayMs.hashCode()
            return result
        }
    }

    // --- Basic controls ---

    data object Beep : WheelCommand()
    data class SetLight(val enabled: Boolean) : WheelCommand()
    data class SetLightMode(val mode: Int) : WheelCommand()
    data class SetLed(val enabled: Boolean) : WheelCommand()
    data class SetLedMode(val mode: Int) : WheelCommand()
    data class SetStrobeMode(val mode: Int) : WheelCommand()
    data class SetPedalsMode(val mode: Int) : WheelCommand()
    data class SetAlarmMode(val mode: Int) : WheelCommand()
    data object Calibrate : WheelCommand()
    data object PowerOff : WheelCommand()
    data class SetLock(val locked: Boolean) : WheelCommand()
    data object ResetTrip : WheelCommand()

    // --- Speed & alarm settings ---

    data class SetMaxSpeed(val speed: Int) : WheelCommand()
    data class SetAlarmSpeed(val speed: Int, val num: Int) : WheelCommand()
    data class SetAlarmEnabled(val enabled: Boolean, val num: Int) : WheelCommand()
    data class SetLimitedMode(val enabled: Boolean) : WheelCommand()
    data class SetLimitedSpeed(val speed: Int) : WheelCommand()

    // --- Lighting ---

    data class SetTailLight(val enabled: Boolean) : WheelCommand()
    data class SetDrl(val enabled: Boolean) : WheelCommand()
    data class SetLedColor(val value: Int, val ledNum: Int) : WheelCommand()
    data class SetLightBrightness(val value: Int) : WheelCommand()

    // --- Ride modes & behavior ---

    data class SetHandleButton(val enabled: Boolean) : WheelCommand()
    data class SetBrakeAssist(val enabled: Boolean) : WheelCommand()
    data class SetTransportMode(val enabled: Boolean) : WheelCommand()
    data class SetRideMode(val enabled: Boolean) : WheelCommand()
    /** @see SetLowBatteryRiding — same sub-command (0x37) on InMotion V2 */
    data class SetGoHomeMode(val enabled: Boolean) : WheelCommand()
    /** @see SetPerformanceMode — same sub-command (0x24) on InMotion V2 */
    data class SetFancierMode(val enabled: Boolean) : WheelCommand()
    data class SetRollAngleMode(val mode: Int) : WheelCommand()

    // --- Audio ---

    data class SetMute(val enabled: Boolean) : WheelCommand()
    data class SetSpeakerVolume(val volume: Int) : WheelCommand()
    data class SetBeeperVolume(val volume: Int) : WheelCommand()

    // --- Thermal ---

    data class SetFanQuiet(val enabled: Boolean) : WheelCommand()
    data class SetFan(val enabled: Boolean) : WheelCommand()

    // --- Pedal tuning ---

    data class SetPedalTilt(val angle: Int) : WheelCommand()
    data class SetPedalSensitivity(val sensitivity: Int) : WheelCommand()

    // --- Units ---

    data class SetMilesMode(val enabled: Boolean) : WheelCommand()

    // --- Cutout angle (Begode) ---

    data class SetCutoutAngle(val angle: Int) : WheelCommand()

    // --- Begode extended settings ---

    data class SetWeakMagnetism(val level: Int) : WheelCommand()
    data class SetExtendedRollAngle(val level: Int) : WheelCommand()
    data class SetPowerAlarm(val percentage: Int) : WheelCommand()
    data class SetPlateProtection(val enabled: Boolean) : WheelCommand()

    // --- InMotion V2 extended settings (command bytes TBD via BLE capture) ---

    // Berm Angle
    data class SetBermAngleMode(val enabled: Boolean) : WheelCommand()
    data class SetBermAngle(val angle: Int) : WheelCommand()

    // Ride Behavior
    data class SetTurningSensitivity(val sensitivity: Int) : WheelCommand()
    data class SetOnePedalMode(val enabled: Boolean) : WheelCommand()
    data class SetSpeedingBrakingMode(val enabled: Boolean) : WheelCommand()
    data class SetSpeedingBrakingAngle(val angle: Int) : WheelCommand()
    data class SetSoundWave(val enabled: Boolean) : WheelCommand()
    data class SetSoundWaveSensitivity(val sensitivity: Int) : WheelCommand()
    data class SetSafeSpeedLimit(val enabled: Boolean) : WheelCommand()
    data class SetBackwardOverspeedAlert(val enabled: Boolean) : WheelCommand()

    // Lighting (extended)
    data class SetTailLightMode(val mode: Int) : WheelCommand()
    data class SetTurnSignalMode(val mode: Int) : WheelCommand()
    data class SetLogoLightBrightness(val brightness: Int) : WheelCommand()
    data class SetAutoHeadlight(val enabled: Boolean) : WheelCommand()
    data class SetLightEffect(val enabled: Boolean) : WheelCommand()
    data class SetLightEffectMode(val mode: Int) : WheelCommand()

    // Battery & Safety
    data class SetTwoBatteryMode(val enabled: Boolean) : WheelCommand()
    data class SetLowBatterySafeMode(val enabled: Boolean) : WheelCommand()
    data class SetSpinKill(val enabled: Boolean) : WheelCommand()
    data class SetCruise(val enabled: Boolean) : WheelCommand()
    data class SetLoadDetect(val enabled: Boolean) : WheelCommand()
    data class SetStandbyTime(val minutes: Int) : WheelCommand()
    data class SetChargeLimit(val percentage: Int) : WheelCommand()
    data class SetMotorSound(val enabled: Boolean) : WheelCommand()
    data class SetMotorSoundSensitivity(val sensitivity: Int) : WheelCommand()
    data class SetScreenAutoOff(val enabled: Boolean) : WheelCommand()
    data class SetExtendedLateralTilt(val enabled: Boolean) : WheelCommand()
    data class SetSplitRidingModes(val enabled: Boolean) : WheelCommand()
    data class SetSplitRidingModesSettings(val acceleration: Int, val braking: Int) : WheelCommand()
    data class SetSpeedAlarms(val alarm1: Int, val alarm2: Int) : WheelCommand()
    /** Same as SetGoHomeMode — sub-command 0x37 on InMotion V2 */
    data class SetLowBatteryRiding(val enabled: Boolean) : WheelCommand()
    data class SetMotorNoLoadDetection(val enabled: Boolean) : WheelCommand()
    /** Same as SetFancierMode — sub-command 0x24 on InMotion V2 */
    data class SetPerformanceMode(val enabled: Boolean) : WheelCommand()

    // --- InMotion P6 settings ---

    data class SetAutoLock(val enabled: Boolean) : WheelCommand()
    data class SetBalanceAngle(val angle: Int) : WheelCommand()
    data class SetChargingCurrent(val ac220v: Int, val ac110v: Int) : WheelCommand()
    data class SetIgnoreTirePressure(val enabled: Boolean) : WheelCommand()
    data class SetMinTirePressure(val value1: Int, val value2: Int) : WheelCommand()
    data class SetRideConnectSwitch(val enabled: Boolean) : WheelCommand()
    data class SetRideConnectLowBattery(val enabled: Boolean) : WheelCommand()
    data class SetSpeedTiltbackEnable(val enabled: Boolean) : WheelCommand()

    // --- DarknessBot-discovered settings (command bytes TBD via BLE capture) ---

    data class SetExtremeMode(val enabled: Boolean) : WheelCommand()
    data class SetTrickMode(val enabled: Boolean) : WheelCommand()
    data class SetRecuperationMode(val mode: Int) : WheelCommand()
    data class SetEqualizerMode(val mode: Int) : WheelCommand()
    data class SetPwmLimit(val limit: Int) : WheelCommand()
    data class SetBrakeLights(val enabled: Boolean) : WheelCommand()
    data class SetLightSensor(val enabled: Boolean) : WheelCommand()

    // --- Veteran/Leaperkim extended settings ---

    data class SetHighSpeedMode(val enabled: Boolean) : WheelCommand()
    data class SetLowVoltageMode(val enabled: Boolean) : WheelCommand()
    data class SetKeyTone(val value: Int) : WheelCommand()
    data class SetScreenBacklight(val value: Int) : WheelCommand()
    data class SetStopSpeed(val speed: Int) : WheelCommand()
    data class SetVeteranPwmLimit(val limit: Int) : WheelCommand()
    data class SetVoltageCorrection(val value: Int) : WheelCommand()
    data class SetMaxChargeVoltage(val value: Int) : WheelCommand()
    data class SetBrakePressureAlarm(val value: Int) : WheelCommand()
    data class SetLateralCutoffAngle(val angle: Int) : WheelCommand()
    data class SetDynamicAssist(val value: Int) : WheelCommand()
    data class SetAccelerationLimit(val value: Int) : WheelCommand()
    data class SetWheelDisplayUnit(val miles: Boolean) : WheelCommand()

    /** Veteran lock/unlock with numeric password (e.g. "000000"). */
    data class SetVeteranLock(val locked: Boolean, val password: String) : WheelCommand()

    /** Request event log download from the wheel (Veteran/Leaperkim). */
    data object RequestEventLog : WheelCommand()

    // --- BMS requests (Kingsong) ---

    /**
     * Request BMS data. bmsNum: 1 or 2, dataType: 0=serial, 1=moreData, 2=firmware
     */
    data class RequestBmsData(val bmsNum: Int, val dataType: Int) : WheelCommand()

    // --- Kingsong alarm+speed combo ---

    /**
     * Set all three alarm speeds and max speed in one command (Kingsong-specific).
     */
    data class SetKingsongAlarms(
        val alarm1Speed: Int,
        val alarm2Speed: Int,
        val alarm3Speed: Int,
        val maxSpeed: Int
    ) : WheelCommand()

    /**
     * Request alarm settings and max speed from the wheel.
     */
    data object RequestAlarmSettings : WheelCommand()
}

/**
 * Factory for creating wheel decoders.
 */
interface WheelDecoderFactory {
    /**
     * Create a decoder for the specified wheel type.
     */
    fun createDecoder(wheelType: WheelType): WheelDecoder?

    /**
     * Get all supported wheel types.
     */
    fun supportedTypes(): List<WheelType>
}
