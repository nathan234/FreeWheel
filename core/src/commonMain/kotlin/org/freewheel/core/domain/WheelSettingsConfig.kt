package org.freewheel.core.domain

/**
 * Shared configuration defining which settings controls appear for each wheel type.
 * Both Android and iOS render their settings screens from this configuration.
 */
object WheelSettingsConfig {

    fun sections(wheelType: WheelType): List<SettingsSection> = when (wheelType) {
        WheelType.KINGSONG -> kingsongSections()
        WheelType.GOTWAY, WheelType.GOTWAY_VIRTUAL -> gotwaySections()
        WheelType.VETERAN -> veteranSections()
        WheelType.LEAPERKIM -> leaperkimSections()
        WheelType.NINEBOT_Z -> ninebotZSections()
        WheelType.INMOTION -> inmotionSections()
        WheelType.INMOTION_V2 -> inmotionV2Sections()
        else -> emptyList()
    }

    private fun kingsongSections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Picker("Light Mode", listOf("Off", "On", "Auto"), SettingsCommandId.LIGHT_MODE),
            ControlSpec.Picker("LED Mode", (0..7).map { "$it" }, SettingsCommandId.LED_MODE),
            ControlSpec.Picker("Strobe Mode", (0..3).map { "$it" }, SettingsCommandId.STROBE_MODE)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Segmented("Pedals Mode", listOf("Hard", "Medium", "Soft"), SettingsCommandId.PEDALS_MODE)
        )),
        SettingsSection("Dangerous Actions", listOf(
            calibrateButton(),
            powerOffButton()
        ))
    )

    private fun gotwaySections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Picker("Light Mode", listOf("Off", "On", "Strobe"), SettingsCommandId.LIGHT_MODE),
            ControlSpec.Picker("LED Mode", (0..9).map { "$it" }, SettingsCommandId.LED_MODE)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Segmented("Pedals Mode", listOf("Hard", "Medium", "Soft"), SettingsCommandId.PEDALS_MODE),
            ControlSpec.Segmented("Roll Angle", listOf("Low", "Medium", "High"), SettingsCommandId.ROLL_ANGLE_MODE),
            ControlSpec.Slider("Cutout Angle", 45, 90, "\u00B0", 70, SettingsCommandId.CUTOUT_ANGLE, step = 5),
            ControlSpec.Slider("Pedal Tilt", 0, 9, "", 5, SettingsCommandId.PEDAL_TILT),
            ControlSpec.Slider("Weak Magnetism", 0, 6, "", 0, SettingsCommandId.WEAK_MAGNETISM),
            ControlSpec.Slider("Extended Roll Angle", 0, 9, "", 5, SettingsCommandId.EXTENDED_ROLL_ANGLE)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Beeper Volume", 1, 9, "", 5, SettingsCommandId.BEEPER_VOLUME)
        )),
        SettingsSection("Safety", listOf(
            ControlSpec.Toggle("Plate Protection", SettingsCommandId.PLATE_PROTECTION),
            ControlSpec.Slider("Power Alarm", 50, 90, "%", 70, SettingsCommandId.POWER_ALARM)
        )),
        SettingsSection("Dangerous Actions", listOf(
            calibrateButton()
        ))
    )

    private fun veteranSections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Toggle("Headlight", SettingsCommandId.LIGHT_MODE),
            ControlSpec.Slider("Screen Backlight", 0, 100, "%", 50, SettingsCommandId.SCREEN_BACKLIGHT)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Segmented("Pedals Mode", listOf("Hard", "Medium", "Soft"), SettingsCommandId.PEDALS_MODE),
            ControlSpec.Slider("Alarm Speed", 10, 80, "km/h", 50, SettingsCommandId.ALARM_SPEED_1),
            ControlSpec.Slider("Pedal Tilt", -8, 8, "\u00B0", 0, SettingsCommandId.PEDAL_TILT),
            ControlSpec.Slider("Stop Speed", 10, 120, "km/h", 60, SettingsCommandId.STOP_SPEED),
            ControlSpec.Slider("PWM Limit", 30, 100, "%", 80, SettingsCommandId.VETERAN_PWM_LIMIT),
            ControlSpec.Toggle("Transport Mode", SettingsCommandId.TRANSPORT_MODE),
            ControlSpec.Toggle("High Speed Mode", SettingsCommandId.HIGH_SPEED_MODE),
            ControlSpec.Toggle("Low Voltage Mode", SettingsCommandId.LOW_VOLTAGE_MODE)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Key Tone", 0, 100, "%", 50, SettingsCommandId.KEY_TONE)
        )),
        SettingsSection("Battery", listOf(
            ControlSpec.Slider("Voltage Correction", -15, 15, "", 0, SettingsCommandId.VOLTAGE_CORRECTION),
            ControlSpec.Slider("Max Charge Voltage", 0, 120, "", 100, SettingsCommandId.MAX_CHARGE_VOLTAGE)
        )),
        SettingsSection("Dangerous Actions", listOf(
            ControlSpec.Slider("Lateral Cutoff Angle", 35, 90, "\u00B0", 70, SettingsCommandId.LATERAL_CUTOFF_ANGLE),
            lockToggle(),
            calibrateButton(),
            powerOffButton(),
            resetTripButton()
        ))
    )

    private fun leaperkimSections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Toggle("Headlight", SettingsCommandId.LIGHT_MODE),
            ControlSpec.Toggle("LEDs", SettingsCommandId.LED)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Toggle("Handle Button", SettingsCommandId.HANDLE_BUTTON),
            ControlSpec.Toggle("Ride Mode", SettingsCommandId.RIDE_MODE),
            ControlSpec.Slider("Max Speed", 5, 50, "km/h", 30, SettingsCommandId.MAX_SPEED),
            ControlSpec.Slider("Pedal Tilt", -8, 8, "\u00B0", 0, SettingsCommandId.PEDAL_TILT),
            ControlSpec.Slider("Pedal Sensitivity", 0, 100, "%", 50, SettingsCommandId.PEDAL_SENSITIVITY)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Speaker Volume", 0, 100, "", 50, SettingsCommandId.SPEAKER_VOLUME)
        )),
        SettingsSection("Dangerous Actions", listOf(
            lockToggle(),
            powerOffButton()
        ))
    )

    private fun ninebotZSections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Toggle("Headlight", SettingsCommandId.LIGHT_MODE),
            ControlSpec.Toggle("DRL", SettingsCommandId.DRL),
            ControlSpec.Toggle("Tail Light", SettingsCommandId.TAIL_LIGHT),
            ControlSpec.Picker("LED Mode", listOf("Off") + (1..7).map { "Type $it" }, SettingsCommandId.LED_MODE)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Toggle("Handle Button", SettingsCommandId.HANDLE_BUTTON),
            ControlSpec.Toggle("Brake Assistant", SettingsCommandId.BRAKE_ASSIST),
            ControlSpec.Slider("Pedal Sensitivity", 0, 4, "", 0, SettingsCommandId.PEDAL_SENSITIVITY)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Speaker Volume", 0, 127, "", 50, SettingsCommandId.SPEAKER_VOLUME)
        )),
        SettingsSection("Wheel Alarms", listOf(
            ControlSpec.Toggle("Alarm 1", SettingsCommandId.ALARM_ENABLED_1),
            ControlSpec.Slider("Alarm 1 Speed", 0, 60, "km/h", 30, SettingsCommandId.ALARM_SPEED_1,
                visibleWhen = SettingsCommandId.ALARM_ENABLED_1),
            ControlSpec.Toggle("Alarm 2", SettingsCommandId.ALARM_ENABLED_2),
            ControlSpec.Slider("Alarm 2 Speed", 0, 60, "km/h", 35, SettingsCommandId.ALARM_SPEED_2,
                visibleWhen = SettingsCommandId.ALARM_ENABLED_2),
            ControlSpec.Toggle("Alarm 3", SettingsCommandId.ALARM_ENABLED_3),
            ControlSpec.Slider("Alarm 3 Speed", 0, 60, "km/h", 40, SettingsCommandId.ALARM_SPEED_3,
                visibleWhen = SettingsCommandId.ALARM_ENABLED_3)
        )),
        SettingsSection("Speed Limit", listOf(
            ControlSpec.Toggle("Limited Mode", SettingsCommandId.LIMITED_MODE),
            ControlSpec.Slider("Limited Speed", 0, 65, "km/h", 25, SettingsCommandId.LIMITED_SPEED,
                visibleWhen = SettingsCommandId.LIMITED_MODE)
        )),
        SettingsSection("Dangerous Actions", listOf(
            lockToggle(),
            calibrateButton()
        ))
    )

    private fun inmotionSections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Toggle("Headlight", SettingsCommandId.LIGHT_MODE),
            ControlSpec.Toggle("LEDs", SettingsCommandId.LED)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Toggle("Handle Button", SettingsCommandId.HANDLE_BUTTON),
            ControlSpec.Toggle("Ride Mode", SettingsCommandId.RIDE_MODE),
            ControlSpec.Slider("Max Speed", 3, 60, "km/h", 30, SettingsCommandId.MAX_SPEED),
            ControlSpec.Slider("Pedal Tilt", -8, 8, "\u00B0", 0, SettingsCommandId.PEDAL_TILT),
            ControlSpec.Slider("Pedal Sensitivity", 4, 100, "%", 50, SettingsCommandId.PEDAL_SENSITIVITY)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Speaker Volume", 0, 100, "", 50, SettingsCommandId.SPEAKER_VOLUME)
        )),
        SettingsSection("Dangerous Actions", listOf(
            calibrateButton(),
            powerOffButton()
        ))
    )

    private fun inmotionV2Sections() = listOf(
        SettingsSection("Lighting", listOf(
            ControlSpec.Toggle("Headlight", SettingsCommandId.LIGHT_MODE),
            ControlSpec.Toggle("DRL", SettingsCommandId.DRL),
            ControlSpec.Slider("Brightness", 0, 100, "%", 50, SettingsCommandId.LIGHT_BRIGHTNESS),
            ControlSpec.Toggle("Auto Headlight", SettingsCommandId.AUTO_HEADLIGHT),
            ControlSpec.Slider("Logo Light Brightness", 0, 100, "%", 50, SettingsCommandId.LOGO_LIGHT_BRIGHTNESS),
            ControlSpec.Picker("Tail Light Mode", listOf("Off", "Highlight", "Hazard"), SettingsCommandId.TAIL_LIGHT_MODE),
            ControlSpec.Picker("Turn Signal Mode", listOf("Off", "Always On", "Common", "Strobe", "Sync Tail"), SettingsCommandId.TURN_SIGNAL_MODE),
            ControlSpec.Toggle("Light Effects", SettingsCommandId.LIGHT_EFFECT)
        )),
        SettingsSection("Ride", listOf(
            ControlSpec.Toggle("Handle Button", SettingsCommandId.HANDLE_BUTTON),
            ControlSpec.Toggle("Ride Mode", SettingsCommandId.RIDE_MODE),
            ControlSpec.Toggle("Go Home Mode", SettingsCommandId.GO_HOME_MODE),
            ControlSpec.Toggle("Fancier Mode", SettingsCommandId.FANCIER_MODE),
            ControlSpec.Toggle("Transport Mode", SettingsCommandId.TRANSPORT_MODE),
            ControlSpec.Slider("Max Speed", 3, 60, "km/h", 30, SettingsCommandId.MAX_SPEED),
            ControlSpec.Slider("Pedal Tilt", -10, 10, "\u00B0", 0, SettingsCommandId.PEDAL_TILT),
            ControlSpec.Slider("Pedal Sensitivity", 0, 100, "%", 50, SettingsCommandId.PEDAL_SENSITIVITY),
            ControlSpec.Toggle("One Pedal Mode", SettingsCommandId.ONE_PEDAL_MODE),
            ControlSpec.Toggle("Cruise", SettingsCommandId.CRUISE),
            ControlSpec.Slider("Turning Sensitivity", 0, 100, "%", 50, SettingsCommandId.TURNING_SENSITIVITY)
        )),
        SettingsSection("Berm Angle", listOf(
            ControlSpec.Toggle("Berm Angle Mode", SettingsCommandId.BERM_ANGLE_MODE),
            ControlSpec.Slider("Berm Angle", 0, 45, "\u00B0", 0, SettingsCommandId.BERM_ANGLE,
                visibleWhen = SettingsCommandId.BERM_ANGLE_MODE)
        )),
        SettingsSection("Braking", listOf(
            ControlSpec.Toggle("Speeding-Braking Feedback", SettingsCommandId.SPEEDING_BRAKING_MODE),
            ControlSpec.Slider("Speeding-Braking Angle", 0, 45, "\u00B0", 0, SettingsCommandId.SPEEDING_BRAKING_ANGLE,
                visibleWhen = SettingsCommandId.SPEEDING_BRAKING_MODE)
        )),
        SettingsSection("Audio", listOf(
            ControlSpec.Slider("Speaker Volume", 0, 100, "", 50, SettingsCommandId.SPEAKER_VOLUME),
            ControlSpec.Toggle("Mute", SettingsCommandId.MUTE),
            ControlSpec.Toggle("Sound Wave", SettingsCommandId.SOUND_WAVE),
            ControlSpec.Slider("Sound Wave Sensitivity", 0, 100, "%", 50, SettingsCommandId.SOUND_WAVE_SENSITIVITY,
                visibleWhen = SettingsCommandId.SOUND_WAVE)
        )),
        SettingsSection("Thermal", listOf(
            ControlSpec.Toggle("Fan", SettingsCommandId.FAN),
            ControlSpec.Toggle("Fan Quiet Mode", SettingsCommandId.FAN_QUIET)
        )),
        SettingsSection("Safety", listOf(
            ControlSpec.Toggle("Safe Speed Limit (25 km/h)", SettingsCommandId.SAFE_SPEED_LIMIT),
            ControlSpec.Toggle("Backward Overspeed Alert", SettingsCommandId.BACKWARD_OVERSPEED_ALERT),
            ControlSpec.Toggle("Spin Kill", SettingsCommandId.SPIN_KILL),
            ControlSpec.Toggle("Load Detect", SettingsCommandId.LOAD_DETECT)
        )),
        SettingsSection("Battery", listOf(
            ControlSpec.Toggle("Two Battery Mode", SettingsCommandId.TWO_BATTERY_MODE),
            ControlSpec.Toggle("Low Battery Safe Mode", SettingsCommandId.LOW_BATTERY_SAFE_MODE),
            ControlSpec.Slider("Charge Limit", 50, 100, "%", 100, SettingsCommandId.CHARGE_LIMIT)
        )),
        SettingsSection("System", listOf(
            ControlSpec.Slider("Standby Time", 1, 60, "min", 15, SettingsCommandId.STANDBY_TIME)
        )),
        SettingsSection("Dangerous Actions", listOf(
            lockToggle(),
            calibrateButton(),
            powerOffButton()
        ))
    )

    // Shared dangerous action definitions

    private fun calibrateButton() = ControlSpec.DangerousButton(
        label = "Calibrate Wheel",
        confirmTitle = "Calibrate Wheel",
        confirmMessage = "Place the wheel upright on a flat surface before calibrating. The wheel must be stationary.",
        commandId = SettingsCommandId.CALIBRATE
    )

    private fun powerOffButton() = ControlSpec.DangerousButton(
        label = "Power Off",
        confirmTitle = "Power Off",
        confirmMessage = "Are you sure you want to power off the wheel?",
        commandId = SettingsCommandId.POWER_OFF
    )

    private fun resetTripButton() = ControlSpec.DangerousButton(
        label = "Reset Trip",
        confirmTitle = "Reset Trip",
        confirmMessage = "This will reset the trip distance counter to zero.",
        commandId = SettingsCommandId.RESET_TRIP
    )

    private fun lockToggle() = ControlSpec.DangerousToggle(
        label = "Lock Wheel",
        confirmTitle = "Lock Wheel",
        confirmMessage = "Locking the wheel will prevent it from riding. Unlock via this app.",
        commandId = SettingsCommandId.LOCK
    )
}
