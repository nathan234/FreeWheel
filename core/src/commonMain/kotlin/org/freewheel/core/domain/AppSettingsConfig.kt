package org.freewheel.core.domain

import org.freewheel.core.domain.dashboard.UnitCategory

/**
 * Single source of truth for which settings controls appear on the app settings screen.
 *
 * Both Android (Compose) and iOS (SwiftUI) render their settings screens from this
 * configuration, ensuring identical structure across platforms. Platform layers iterate
 * the sections, evaluate visibility via [AppSettingVisibilityEvaluator], and render
 * each [AppSettingSpec] using native controls.
 *
 * Wheel-specific settings (pedals mode, light mode, etc.) are handled separately by
 * [WheelSettingsConfig] and rendered via the [WHEEL_SETTINGS_TITLE] placeholder section.
 */
object AppSettingsConfig {

    /** Title used for the wheel settings placeholder section. */
    const val WHEEL_SETTINGS_TITLE = "Wheel Settings"

    // Reusable visibility conditions
    private val alarmsOn = AppSettingVisibility.WhenEnabled(AppSettingId.ALARMS_ENABLED)
    private val pwmOn = AppSettingVisibility.WhenEnabled(AppSettingId.PWM_BASED_ALARMS)
    private val pwmOff = AppSettingVisibility.WhenDisabled(AppSettingId.PWM_BASED_ALARMS)
    private val alarmsAndPwm = AppSettingVisibility.AllOf(listOf(alarmsOn, pwmOn))
    private val alarmsAndNotPwm = AppSettingVisibility.AllOf(listOf(alarmsOn, pwmOff))
    private val torchOn = AppSettingVisibility.WhenEnabled(AppSettingId.AUTO_TORCH_ENABLED)
    private val veteranOrLeaperkim = AppSettingVisibility.WhenWheelType(
        setOf(WheelType.VETERAN, WheelType.LEAPERKIM)
    )

    fun sections(): List<AppSettingsSection> = listOf(
        unitsSection(),
        alarmsSection(),
        pwmThresholdsSection(),
        preWarningsSection(),
        speedAlarmsSection(),
        otherAlarmsSection(),
        connectionSection(),
        loggingSection(),
        autoTorchSection(),
        wheelSettingsSection(),
        interfaceSection(),
        developerSection(),
        aboutSection(),
        closeAppSection()
    )

    // ------------------------------------------------------------------
    // Section builders
    // ------------------------------------------------------------------

    private fun unitsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_UNITS,
        controls = listOf(
            AppSettingSpec.Toggle(SettingsLabels.USE_MPH, AppSettingId.USE_MPH),
            AppSettingSpec.Toggle(SettingsLabels.USE_FAHRENHEIT, AppSettingId.USE_FAHRENHEIT)
        )
    )

    private fun alarmsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_ALARMS,
        controls = listOf(
            AppSettingSpec.Toggle(SettingsLabels.ENABLE_ALARMS, AppSettingId.ALARMS_ENABLED),
            AppSettingSpec.Picker(
                label = SettingsLabels.ALARM_ACTION,
                options = AlarmAction.entries.map { it.label },
                settingId = AppSettingId.ALARM_ACTION,
                visibility = alarmsOn
            ),
            AppSettingSpec.Toggle(
                label = SettingsLabels.PWM_BASED_ALARMS,
                settingId = AppSettingId.PWM_BASED_ALARMS,
                visibility = alarmsOn
            )
        ),
        footer = SettingsLabels.PWM_DESCRIPTION
    )

    private fun pwmThresholdsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_PWM_THRESHOLDS,
        visibility = alarmsAndPwm,
        controls = listOf(
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_FACTOR_1,
                min = 0, max = 99, unit = "%",
                settingId = AppSettingId.ALARM_FACTOR_1
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_FACTOR_2,
                min = 0, max = 99, unit = "%",
                settingId = AppSettingId.ALARM_FACTOR_2
            )
        )
    )

    private fun preWarningsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_PRE_WARNINGS,
        visibility = alarmsAndPwm,
        controls = listOf(
            AppSettingSpec.Slider(
                label = SettingsLabels.WARNING_SPEED,
                min = 0, max = 120, unit = "",
                settingId = AppSettingId.WARNING_SPEED,
                unitCategory = UnitCategory.SPEED
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.WARNING_PWM,
                min = 0, max = 99, unit = "%",
                settingId = AppSettingId.WARNING_PWM
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.WARNING_PERIOD,
                min = 0, max = 60, unit = "sec",
                settingId = AppSettingId.WARNING_SPEED_PERIOD,
                visibility = AppSettingVisibility.WhenAnyNonZero(
                    listOf(AppSettingId.WARNING_SPEED, AppSettingId.WARNING_PWM)
                )
            )
        ),
        footer = SettingsLabels.PRE_WARNING_HINT
    )

    private fun speedAlarmsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_SPEED_ALARMS,
        visibility = alarmsAndNotPwm,
        controls = listOf(
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_1_SPEED,
                min = 0, max = 100, unit = "",
                settingId = AppSettingId.ALARM_1_SPEED,
                unitCategory = UnitCategory.SPEED
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_1_BATTERY,
                min = 0, max = 100, unit = "%",
                settingId = AppSettingId.ALARM_1_BATTERY
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_2_SPEED,
                min = 0, max = 100, unit = "",
                settingId = AppSettingId.ALARM_2_SPEED,
                unitCategory = UnitCategory.SPEED
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_2_BATTERY,
                min = 0, max = 100, unit = "%",
                settingId = AppSettingId.ALARM_2_BATTERY
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_3_SPEED,
                min = 0, max = 100, unit = "",
                settingId = AppSettingId.ALARM_3_SPEED,
                unitCategory = UnitCategory.SPEED
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.ALARM_3_BATTERY,
                min = 0, max = 100, unit = "%",
                settingId = AppSettingId.ALARM_3_BATTERY
            )
        ),
        footer = SettingsLabels.DISABLE_HINT
    )

    private fun otherAlarmsSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_OTHER_ALARMS,
        visibility = alarmsOn,
        controls = listOf(
            AppSettingSpec.Slider(
                label = SettingsLabels.CURRENT_ALARM,
                min = 0, max = 100, unit = "A",
                settingId = AppSettingId.ALARM_CURRENT
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.PHASE_CURRENT_ALARM,
                min = 0, max = 400, unit = "A",
                settingId = AppSettingId.ALARM_PHASE_CURRENT
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.TEMPERATURE_ALARM,
                min = 0, max = 80, unit = "",
                settingId = AppSettingId.ALARM_TEMPERATURE,
                unitCategory = UnitCategory.TEMPERATURE
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.MOTOR_TEMP_ALARM,
                min = 0, max = 200, unit = "",
                settingId = AppSettingId.ALARM_MOTOR_TEMPERATURE,
                unitCategory = UnitCategory.TEMPERATURE
            ),
            AppSettingSpec.Slider(
                label = SettingsLabels.BATTERY_ALARM,
                min = 0, max = 100, unit = "%",
                settingId = AppSettingId.ALARM_BATTERY
            ),
            AppSettingSpec.Toggle(
                label = SettingsLabels.WHEEL_ALARM,
                settingId = AppSettingId.ALARM_WHEEL
            )
        ),
        footer = SettingsLabels.DISABLE_HINT
    )

    private fun connectionSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_CONNECTION,
        controls = listOf(
            AppSettingSpec.Toggle(SettingsLabels.AUTO_RECONNECT, AppSettingId.AUTO_RECONNECT),
            AppSettingSpec.Toggle(SettingsLabels.SHOW_UNKNOWN_DEVICES, AppSettingId.SHOW_UNKNOWN_DEVICES)
        ),
        footer = SettingsLabels.RECONNECT_HINT
    )

    private fun loggingSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_LOGGING,
        controls = listOf(
            AppSettingSpec.Toggle(SettingsLabels.AUTO_START_LOGGING, AppSettingId.AUTO_LOG),
            AppSettingSpec.Toggle(SettingsLabels.INCLUDE_GPS, AppSettingId.LOG_LOCATION_DATA),
            AppSettingSpec.Toggle(SettingsLabels.AUTO_CAPTURE, AppSettingId.AUTO_CAPTURE)
        ),
        footer = SettingsLabels.GPS_HINT
    )

    private fun autoTorchSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_AUTO_TORCH,
        controls = listOf(
            AppSettingSpec.Toggle(SettingsLabels.AUTO_TORCH_ENABLED, AppSettingId.AUTO_TORCH_ENABLED),
            AppSettingSpec.Slider(
                label = SettingsLabels.AUTO_TORCH_SPEED_THRESHOLD,
                min = 0, max = 60, unit = "",
                settingId = AppSettingId.AUTO_TORCH_SPEED_THRESHOLD,
                unitCategory = UnitCategory.SPEED,
                visibility = torchOn
            ),
            AppSettingSpec.Toggle(
                label = SettingsLabels.AUTO_TORCH_USE_SUNSET,
                settingId = AppSettingId.AUTO_TORCH_USE_SUNSET,
                visibility = torchOn
            )
        ),
        footer = SettingsLabels.AUTO_TORCH_HINT
    )

    /**
     * Placeholder section for wheel-specific settings.
     * Platform layers detect this by [WHEEL_SETTINGS_TITLE] and delegate to
     * [WheelSettingsConfig] rendering instead.
     */
    private fun wheelSettingsSection() = AppSettingsSection(
        title = WHEEL_SETTINGS_TITLE,
        visibility = AppSettingVisibility.WhenConnected,
        controls = emptyList()
    )

    private fun interfaceSection() = AppSettingsSection(
        title = "Interface",
        controls = listOf(
            AppSettingSpec.NavLink(
                "Customize Navigation",
                AppSettingsDestinations.CUSTOMIZE_NAVIGATION
            )
        )
    )

    private fun developerSection() = AppSettingsSection(
        title = "Developer",
        controls = listOf(
            AppSettingSpec.NavLink(
                "BLE Capture",
                AppSettingsDestinations.BLE_CAPTURE
            ),
            AppSettingSpec.NavLink(
                SettingsLabels.CONNECTION_ERROR_LOG,
                AppSettingsDestinations.CONNECTION_ERROR_LOG
            ),
            AppSettingSpec.NavLink(
                label = "Wheel Event Log",
                destinationId = AppSettingsDestinations.WHEEL_EVENT_LOG,
                visibility = veteranOrLeaperkim
            ),
            AppSettingSpec.NavLink(
                "Diagnostics",
                AppSettingsDestinations.DIAGNOSTICS
            )
        )
    )

    private fun aboutSection() = AppSettingsSection(
        title = SettingsLabels.SECTION_ABOUT,
        controls = listOf(
            AppSettingSpec.StaticInfo(SettingsLabels.VERSION, AppSettingsValueIds.APP_VERSION),
            AppSettingSpec.StaticInfo("Build Date", AppSettingsValueIds.BUILD_DATE),
            AppSettingSpec.ExternalLink(SettingsLabels.GITHUB_REPOSITORY, AppConstants.GITHUB_REPO_URL)
        )
    )

    private fun closeAppSection() = AppSettingsSection(
        title = "",
        controls = listOf(
            AppSettingSpec.ActionButton(
                label = SettingsLabels.CLOSE_APP,
                actionId = AppSettingsActions.CLOSE_APP,
                isDestructive = true
            )
        )
    )
}
