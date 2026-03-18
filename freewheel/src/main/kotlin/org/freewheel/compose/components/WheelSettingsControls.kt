package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.core.domain.ControlSpec
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.SettingsSection
import org.freewheel.core.domain.WheelSettings

@Composable
internal fun SectionCard(
    section: SettingsSection,
    wheelSettings: WheelSettings,
    toggleStates: Map<SettingsCommandId, Boolean>,
    sliderOverrides: Map<SettingsCommandId, Int> = emptyMap(),
    onIntCommand: (SettingsCommandId, Int) -> Unit,
    onBoolCommand: (SettingsCommandId, Boolean) -> Unit,
    onDangerousAction: (ControlSpec) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (section.title == "Dangerous Actions")
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (section.title == "Dangerous Actions")
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            section.controls.forEachIndexed { index, control ->
                // Check visibility gating
                if (control is ControlSpec.Slider && control.visibleWhen != null) {
                    val gateValue = toggleStates[control.visibleWhen]
                        ?: control.visibleWhen?.let { SettingsCommandId.entries.find { e -> e == it } }
                            ?.readBool(wheelSettings)
                        ?: false
                    if (!gateValue) return@forEachIndexed
                }

                RenderControl(
                    control = control,
                    wheelSettings = wheelSettings,
                    toggleStates = toggleStates,
                    sliderOverrides = sliderOverrides,
                    onIntCommand = onIntCommand,
                    onBoolCommand = onBoolCommand,
                    onDangerousAction = onDangerousAction
                )

                if (index < section.controls.lastIndex) {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
internal fun RenderControl(
    control: ControlSpec,
    wheelSettings: WheelSettings,
    toggleStates: Map<SettingsCommandId, Boolean>,
    sliderOverrides: Map<SettingsCommandId, Int> = emptyMap(),
    onIntCommand: (SettingsCommandId, Int) -> Unit,
    onBoolCommand: (SettingsCommandId, Boolean) -> Unit,
    onDangerousAction: (ControlSpec) -> Unit
) {
    when (control) {
        is ControlSpec.Toggle -> ToggleControl(control, wheelSettings, toggleStates, onBoolCommand)
        is ControlSpec.Segmented -> SegmentedControl(control, wheelSettings, onIntCommand)
        is ControlSpec.Picker -> PickerControl(control, wheelSettings, onIntCommand)
        is ControlSpec.Slider -> SliderControl(control, wheelSettings, sliderOverrides[control.commandId], onIntCommand)
        is ControlSpec.DangerousButton -> DangerousButtonControl(control, onDangerousAction)
        is ControlSpec.DangerousToggle -> DangerousToggleControl(control, toggleStates, onBoolCommand, onDangerousAction)
    }
}

@Composable
internal fun ToggleControl(
    control: ControlSpec.Toggle,
    wheelSettings: WheelSettings,
    toggleStates: Map<SettingsCommandId, Boolean>,
    onBoolCommand: (SettingsCommandId, Boolean) -> Unit
) {
    val readback = control.commandId.readBool(wheelSettings)
    val checked = toggleStates[control.commandId] ?: readback ?: false

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(control.label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = { onBoolCommand(control.commandId, it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SegmentedControl(
    control: ControlSpec.Segmented,
    wheelSettings: WheelSettings,
    onIntCommand: (SettingsCommandId, Int) -> Unit
) {
    val readback = control.commandId.readInt(wheelSettings)
    var selected by remember(readback) { mutableIntStateOf(readback ?: 0) }

    Column {
        Text(control.label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            control.options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selected == index,
                    onClick = {
                        selected = index
                        onIntCommand(control.commandId, index)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = control.options.size
                    )
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerControl(
    control: ControlSpec.Picker,
    wheelSettings: WheelSettings,
    onIntCommand: (SettingsCommandId, Int) -> Unit
) {
    val readback = control.commandId.readInt(wheelSettings)
    var selected by remember(readback) { mutableIntStateOf(readback?.coerceIn(0, control.options.lastIndex) ?: 0) }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = control.options.getOrElse(selected) { "${selected}" },
            onValueChange = {},
            readOnly = true,
            label = { Text(control.label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            control.options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selected = index
                        expanded = false
                        onIntCommand(control.commandId, index)
                    }
                )
            }
        }
    }
}

@Composable
internal fun SliderControl(
    control: ControlSpec.Slider,
    wheelSettings: WheelSettings,
    persistedValue: Int?,
    onIntCommand: (SettingsCommandId, Int) -> Unit
) {
    val readback = control.commandId.readInt(wheelSettings)
    val initial = readback ?: persistedValue ?: control.defaultValue
    var value by remember(readback) { mutableFloatStateOf(initial.toFloat()) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(control.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${value.toInt()}${if (control.unit.isEmpty()) "" else " ${control.unit}"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onIntCommand(control.commandId, value.toInt()) },
            valueRange = control.min.toFloat()..control.max.toFloat(),
            steps = ((control.max - control.min) / control.step - 1).coerceAtLeast(0)
        )
    }
}

@Composable
internal fun DangerousButtonControl(
    control: ControlSpec.DangerousButton,
    onDangerousAction: (ControlSpec) -> Unit
) {
    TextButton(
        onClick = { onDangerousAction(control) },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text(control.label, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun DangerousToggleControl(
    control: ControlSpec.DangerousToggle,
    toggleStates: Map<SettingsCommandId, Boolean>,
    onBoolCommand: (SettingsCommandId, Boolean) -> Unit,
    onDangerousAction: (ControlSpec) -> Unit
) {
    val checked = toggleStates[control.commandId] ?: false

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            control.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                if (newValue) {
                    onDangerousAction(control)
                } else {
                    onBoolCommand(control.commandId, false)
                }
            }
        )
    }
}
