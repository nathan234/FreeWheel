package org.freewheel.core.domain.settings

import org.freewheel.core.domain.dashboard.UnitCategory

/**
 * Settings UI differences for Nosfet wheels (Apex/Aero/Aeon/Xeno), which share the
 * Veteran protocol but not the Leaperkim app's ranges or feature set.
 *
 * Source: official Nosfet app com.nos.nosfet v1.1.3 (setting activities and seekbar
 * layouts). Labels follow the wheel's own on-screen menu (Apex/Aero manuals).
 */
object NosfetSettingsProfile {

    /** Commands the Nosfet app never sends; hidden and not built for Nosfet wheels. */
    val unsupportedCommands: Set<SettingsCommandId> = setOf(
        SettingsCommandId.DYNAMIC_ASSIST,
        SettingsCommandId.ACCELERATION_LIMIT,
        // No password/lock feature in the Nosfet app.
        SettingsCommandId.LOCK,
    )

    val controlOverrides: Map<SettingsCommandId, ControlSpec> = listOf(
        // layout_set_alam_speed: progress 0..110, speed = progress + 10
        ControlSpec.Slider("Alarm Speed", 10, 120, "km/h", 50, SettingsCommandId.ALARM_SPEED_1, unitCategory = UnitCategory.SPEED),
        // StopSpeedSettingActivity: progress 0..110, value = progress + 10 ("TLT" on the wheel)
        ControlSpec.Slider("Tiltback Speed", 10, 120, "km/h", 60, SettingsCommandId.STOP_SPEED, unitCategory = UnitCategory.SPEED),
        // StopPowerSettingActivity: progress 0..70, value = progress + 30 ("PWT" on the wheel)
        ControlSpec.Slider("PWM Tiltback", 30, 100, "%", 80, SettingsCommandId.VETERAN_PWM_LIMIT),
        // layout_set_safe_angle: progress 0..40, angle = progress + 35 ("STA" on the wheel)
        ControlSpec.Slider("Side Tilt Angle", 35, 75, "\u00B0", 70, SettingsCommandId.LATERAL_CUTOFF_ANGLE),
        // MaxChargePowerSettingActivity: raw 0..70, shown by the app as 145.0 V + raw / 10
        ControlSpec.Slider("Max Charge Voltage", 0, 70, "", 70, SettingsCommandId.MAX_CHARGE_VOLTAGE),
        // BrakeSettingActivity: progress 0..35, value = progress + 90
        ControlSpec.Slider("Brake Pressure Alarm", 90, 125, "%", 100, SettingsCommandId.BRAKE_PRESSURE_ALARM),
        // KeyToneSettingActivity 0..100 ("SND" on the wheel)
        ControlSpec.Slider("Volume", 0, 100, "%", 50, SettingsCommandId.KEY_TONE),
    ).associateBy { it.commandId }
}
