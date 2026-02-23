package com.cooper.wheellog.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.cooper.wheellog.BuildConfig
import com.cooper.wheellog.compose.WheelViewModel
import com.cooper.wheellog.core.domain.AlarmAction
import com.cooper.wheellog.core.domain.CommonLabels
import com.cooper.wheellog.core.domain.SettingsLabels
import com.cooper.wheellog.compose.components.DangerousActionDialog
import com.cooper.wheellog.compose.components.SectionCard
import com.cooper.wheellog.compose.components.StatRow
import com.cooper.wheellog.core.domain.AppConstants
import com.cooper.wheellog.core.domain.ControlSpec
import com.cooper.wheellog.core.domain.PreferenceDefaults
import com.cooper.wheellog.core.domain.PreferenceKeys
import com.cooper.wheellog.core.domain.SettingsCommandId
import com.cooper.wheellog.core.domain.WheelSettingsConfig
import com.cooper.wheellog.core.utils.DisplayUtils

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/WheelLog/Views/SettingsView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Units: MPH toggle, Fahrenheit toggle
//  2. Alarms: Enable toggle, action picker, PWM-based toggle
//  3. PWM thresholds (if PWM-based): Factor 1, Factor 2
//  4. Speed alarms (if not PWM-based): Alarm 1/2/3 speed
//  5. Other alarms: Current, Phase Current, Temperature, Motor Temp, Battery, Wheel Alarm
//  6. Connection: Auto Reconnect, Show Unknown Devices
//  7. Logging: Auto-start, Include GPS
//  8. Wheel settings (config-driven, when connected)
//  9. About: Version, GitHub link
// 10. Close App button
//  Note: iOS has additional PWM pre-warning section; alarm battery thresholds per speed alarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: WheelViewModel) {
    val appConfig = viewModel.appConfig
    val wheelState by viewModel.wheelState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val useMph = viewModel.getGlobalBool(PreferenceKeys.USE_MPH, PreferenceDefaults.USE_MPH)
    val useFahrenheit = viewModel.getGlobalBool(PreferenceKeys.USE_FAHRENHEIT, PreferenceDefaults.USE_FAHRENHEIT)
    val context = LocalContext.current

    // Wheel settings config-driven state
    val wheelSections = remember(wheelState.wheelType) {
        WheelSettingsConfig.sections(wheelState.wheelType)
    }
    val toggleStates = remember { mutableStateMapOf<SettingsCommandId, Boolean>() }
    val sliderOverrides = remember(wheelSections) {
        val map = mutableStateMapOf<SettingsCommandId, Int>()
        for (section in wheelSections) {
            for (control in section.controls) {
                if (control is ControlSpec.Slider) {
                    val saved = viewModel.loadSliderValue(control.commandId)
                    if (saved != null) map[control.commandId] = saved
                }
            }
        }
        map
    }
    var pendingAction by remember { mutableStateOf<ControlSpec?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            SettingsLabels.TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Units section
        SettingsSection(title = SettingsLabels.SECTION_UNITS) {
            SettingsToggle(
                label = SettingsLabels.USE_MPH,
                checked = useMph,
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.USE_MPH, it) }
            )
            HorizontalDivider()
            SettingsToggle(
                label = SettingsLabels.USE_FAHRENHEIT,
                checked = useFahrenheit,
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.USE_FAHRENHEIT, it) }
            )
        }

        // Alarms section
        val alarmsEnabled = viewModel.getPerWheelBool(PreferenceKeys.ALARMS_ENABLED, PreferenceDefaults.ALARMS_ENABLED)
        val pwmBasedAlarms = viewModel.getPerWheelBool(PreferenceKeys.ALTERED_ALARMS, PreferenceDefaults.PWM_BASED_ALARMS)
        SettingsSection(title = SettingsLabels.SECTION_ALARMS) {
            SettingsToggle(
                label = SettingsLabels.ENABLE_ALARMS,
                checked = alarmsEnabled,
                onCheckedChange = { viewModel.setPerWheelBool(PreferenceKeys.ALARMS_ENABLED, it) }
            )

            if (alarmsEnabled) {
                HorizontalDivider()

                // Alarm Action picker
                val alarmActionLabels = AlarmAction.entries.map { it.label }
                var alarmActionExpanded by remember { mutableStateOf(false) }
                val selectedAction = AlarmAction.fromValue(viewModel.getGlobalInt(PreferenceKeys.ALARM_ACTION, 0))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(SettingsLabels.ALARM_ACTION)
                    ExposedDropdownMenuBox(
                        expanded = alarmActionExpanded,
                        onExpandedChange = { alarmActionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedAction.label,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = alarmActionExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        ExposedDropdownMenu(
                            expanded = alarmActionExpanded,
                            onDismissRequest = { alarmActionExpanded = false }
                        ) {
                            alarmActionLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setGlobalInt(PreferenceKeys.ALARM_ACTION, index)
                                        alarmActionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                SettingsToggle(
                    label = SettingsLabels.PWM_BASED_ALARMS,
                    checked = pwmBasedAlarms,
                    onCheckedChange = { viewModel.setPerWheelBool(PreferenceKeys.ALTERED_ALARMS, it) }
                )
                Text(
                    SettingsLabels.PWM_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                HorizontalDivider()

                if (pwmBasedAlarms) {
                    val alarmFactor1 = viewModel.getPerWheelInt(PreferenceKeys.ALARM_FACTOR_1, PreferenceDefaults.ALARM_FACTOR_1)
                    val alarmFactor2 = viewModel.getPerWheelInt(PreferenceKeys.ALARM_FACTOR_2, PreferenceDefaults.ALARM_FACTOR_2)
                    AlarmSlider(
                        label = SettingsLabels.ALARM_FACTOR_1,
                        value = alarmFactor1.toFloat(),
                        range = 0f..100f,
                        displayValue = alarmFactor1,
                        unit = "%",
                        onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_FACTOR_1, it.toInt()) }
                    )
                    AlarmSlider(
                        label = SettingsLabels.ALARM_FACTOR_2,
                        value = alarmFactor2.toFloat(),
                        range = 0f..100f,
                        displayValue = alarmFactor2,
                        unit = "%",
                        onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_FACTOR_2, it.toInt()) }
                    )
                } else {
                    val alarm1Speed = viewModel.getPerWheelInt(PreferenceKeys.ALARM_1_SPEED, PreferenceDefaults.ALARM_1_SPEED)
                    val alarm2Speed = viewModel.getPerWheelInt(PreferenceKeys.ALARM_2_SPEED, PreferenceDefaults.ALARM_2_SPEED)
                    val alarm3Speed = viewModel.getPerWheelInt(PreferenceKeys.ALARM_3_SPEED, PreferenceDefaults.ALARM_3_SPEED)
                    AlarmSlider(
                        label = SettingsLabels.ALARM_1_SPEED,
                        value = alarm1Speed.toFloat(),
                        range = 0f..100f,
                        displayValue = displaySpeed(alarm1Speed, useMph),
                        unit = if (useMph) "mph" else "km/h",
                        onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_1_SPEED, it.toInt()) }
                    )
                    AlarmSlider(
                        label = SettingsLabels.ALARM_2_SPEED,
                        value = alarm2Speed.toFloat(),
                        range = 0f..100f,
                        displayValue = displaySpeed(alarm2Speed, useMph),
                        unit = if (useMph) "mph" else "km/h",
                        onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_2_SPEED, it.toInt()) }
                    )
                    AlarmSlider(
                        label = SettingsLabels.ALARM_3_SPEED,
                        value = alarm3Speed.toFloat(),
                        range = 0f..100f,
                        displayValue = displaySpeed(alarm3Speed, useMph),
                        unit = if (useMph) "mph" else "km/h",
                        onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_3_SPEED, it.toInt()) }
                    )
                }

                val alarmCurrent = viewModel.getPerWheelInt(PreferenceKeys.ALARM_CURRENT, PreferenceDefaults.ALARM_CURRENT)
                val alarmPhaseCurrent = viewModel.getPerWheelInt(PreferenceKeys.ALARM_PHASE_CURRENT, PreferenceDefaults.ALARM_PHASE_CURRENT)
                val alarmTemperature = viewModel.getPerWheelInt(PreferenceKeys.ALARM_TEMPERATURE, PreferenceDefaults.ALARM_TEMPERATURE)
                val alarmMotorTemperature = viewModel.getPerWheelInt(PreferenceKeys.ALARM_MOTOR_TEMPERATURE, PreferenceDefaults.ALARM_MOTOR_TEMPERATURE)
                val alarmBattery = viewModel.getPerWheelInt(PreferenceKeys.ALARM_BATTERY, PreferenceDefaults.ALARM_BATTERY)
                AlarmSlider(
                    label = SettingsLabels.CURRENT_ALARM,
                    value = alarmCurrent.toFloat(),
                    range = 0f..100f,
                    displayValue = alarmCurrent,
                    unit = "A",
                    onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_CURRENT, it.toInt()) }
                )
                AlarmSlider(
                    label = SettingsLabels.PHASE_CURRENT_ALARM,
                    value = alarmPhaseCurrent.toFloat(),
                    range = 0f..400f,
                    displayValue = alarmPhaseCurrent,
                    unit = "A",
                    onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_PHASE_CURRENT, it.toInt()) }
                )
                AlarmSlider(
                    label = SettingsLabels.TEMPERATURE_ALARM,
                    value = alarmTemperature.toFloat(),
                    range = 0f..80f,
                    displayValue = displayTemperature(alarmTemperature, useFahrenheit),
                    unit = if (useFahrenheit) "\u00B0F" else "\u00B0C",
                    onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_TEMPERATURE, it.toInt()) }
                )
                AlarmSlider(
                    label = SettingsLabels.MOTOR_TEMP_ALARM,
                    value = alarmMotorTemperature.toFloat(),
                    range = 0f..200f,
                    displayValue = displayTemperature(alarmMotorTemperature, useFahrenheit),
                    unit = if (useFahrenheit) "\u00B0F" else "\u00B0C",
                    onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_MOTOR_TEMPERATURE, it.toInt()) }
                )
                AlarmSlider(
                    label = SettingsLabels.BATTERY_ALARM,
                    value = alarmBattery.toFloat(),
                    range = 0f..100f,
                    displayValue = alarmBattery,
                    unit = "%",
                    onValueChange = { viewModel.setPerWheelInt(PreferenceKeys.ALARM_BATTERY, it.toInt()) }
                )
                SettingsToggle(
                    label = SettingsLabels.WHEEL_ALARM,
                    checked = viewModel.getPerWheelBool(PreferenceKeys.ALARM_WHEEL, PreferenceDefaults.ALARM_WHEEL),
                    onCheckedChange = { viewModel.setPerWheelBool(PreferenceKeys.ALARM_WHEEL, it) }
                )

                Text(
                    SettingsLabels.DISABLE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Connection section
        SettingsSection(title = SettingsLabels.SECTION_CONNECTION) {
            SettingsToggle(
                label = SettingsLabels.AUTO_RECONNECT,
                checked = viewModel.getGlobalBool(PreferenceKeys.USE_RECONNECT, PreferenceDefaults.USE_RECONNECT),
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.USE_RECONNECT, it) }
            )
            Text(
                SettingsLabels.RECONNECT_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            HorizontalDivider()
            SettingsToggle(
                label = SettingsLabels.SHOW_UNKNOWN_DEVICES,
                checked = viewModel.getGlobalBool(PreferenceKeys.SHOW_UNKNOWN_DEVICES, PreferenceDefaults.SHOW_UNKNOWN_DEVICES),
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.SHOW_UNKNOWN_DEVICES, it) }
            )
        }

        // Logging section
        SettingsSection(title = SettingsLabels.SECTION_LOGGING) {
            SettingsToggle(
                label = SettingsLabels.AUTO_START_LOGGING,
                checked = viewModel.getGlobalBool(PreferenceKeys.AUTO_LOG, PreferenceDefaults.AUTO_LOG),
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.AUTO_LOG, it) }
            )
            HorizontalDivider()
            SettingsToggle(
                label = SettingsLabels.INCLUDE_GPS,
                checked = viewModel.getGlobalBool(PreferenceKeys.LOG_LOCATION_DATA, PreferenceDefaults.LOG_LOCATION_DATA),
                onCheckedChange = { viewModel.setGlobalBool(PreferenceKeys.LOG_LOCATION_DATA, it) }
            )
            Text(
                SettingsLabels.GPS_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Wheel settings (config-driven, when connected)
        if (connectionState.isConnected && wheelSections.isNotEmpty()) {
            for (section in wheelSections) {
                SectionCard(
                    section = section,
                    wheelState = wheelState,
                    toggleStates = toggleStates,
                    sliderOverrides = sliderOverrides,
                    onIntCommand = { id, value ->
                        viewModel.saveSliderValue(id, value)
                        sliderOverrides[id] = value
                        viewModel.executeWheelCommand(id, intValue = value)
                    },
                    onBoolCommand = { id, value ->
                        toggleStates[id] = value
                        viewModel.executeWheelCommand(id, boolValue = value)
                    },
                    onDangerousAction = { control -> pendingAction = control }
                )
            }
        }

        // Decoder mode
        SettingsSection(title = "Decoder") {
            StatRow(
                label = "Mode",
                value = "KMP"
            )
        }

        // UI section
        SettingsSection(title = "Interface") {
            SettingsToggle(
                label = "Use New Compose UI",
                checked = appConfig.useComposeUI,
                onCheckedChange = { appConfig.useComposeUI = it }
            )
            Text(
                "Restart the app to switch between legacy and Compose UI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // About section
        SettingsSection(title = SettingsLabels.SECTION_ABOUT) {
            StatRow(label = SettingsLabels.VERSION, value = BuildConfig.VERSION_NAME)
            StatRow(label = "Build Date", value = BuildConfig.BUILD_DATE)
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.GITHUB_REPO_URL))
                        )
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(SettingsLabels.GITHUB_REPOSITORY)
                Text(
                    "Open",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Close app
        OutlinedButton(
            onClick = {
                viewModel.shutdownService()
                (context as? Activity)?.finishAffinity()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336))
        ) {
            Text(SettingsLabels.CLOSE_APP)
        }

        Spacer(Modifier.height(16.dp))
    }

    // Confirmation dialog for dangerous wheel settings actions
    DangerousActionDialog(
        pendingAction = pendingAction,
        onDismiss = { pendingAction = null },
        onConfirmButton = { commandId ->
            viewModel.executeWheelCommand(commandId)
            pendingAction = null
        },
        onConfirmToggle = { commandId ->
            toggleStates[commandId] = true
            viewModel.executeWheelCommand(commandId, boolValue = true)
            pendingAction = null
        }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AlarmSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayValue: Int,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(
                "$displayValue $unit",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) - 1).toInt().coerceAtLeast(0)
        )
    }
}

private fun displaySpeed(kmh: Int, useMph: Boolean): Int =
    DisplayUtils.convertSpeed(kmh.toDouble(), useMph).toInt()

private fun displayTemperature(celsius: Int, useFahrenheit: Boolean): Int =
    DisplayUtils.convertTemp(celsius.toDouble(), useFahrenheit).toInt()
