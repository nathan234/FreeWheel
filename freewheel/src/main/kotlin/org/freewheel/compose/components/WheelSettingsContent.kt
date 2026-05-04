package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.ControlSpec
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.SettingsSection
import org.freewheel.core.domain.WheelSettings

/**
 * Renders wheel-side settings sections plus the dangerous-action confirmation dialog.
 * Owns the local toggle state, persisted slider overrides, and pending-action state.
 *
 * Used by both WheelSettingsScreen (standalone) and SettingsScreen (embedded inline).
 * iOS has the equivalent in WheelSettingsContent within SettingsView.swift.
 */
@Composable
fun WheelSettingsContent(
    viewModel: WheelViewModel,
    sections: List<SettingsSection>,
    wheelSettings: WheelSettings,
    useMph: Boolean,
    modifier: Modifier = Modifier
) {
    val toggleStates = remember { mutableStateMapOf<SettingsCommandId, Boolean>() }
    val sliderOverrides = remember(sections) {
        mutableStateMapOf<SettingsCommandId, Int>().apply {
            for (section in sections) {
                for (control in section.controls) {
                    if (control is ControlSpec.Slider) {
                        viewModel.loadSliderValue(control.commandId)?.let { put(control.commandId, it) }
                    }
                }
            }
        }
    }
    var pendingAction by remember { mutableStateOf<ControlSpec?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (section in sections) {
            SectionCard(
                section = section,
                wheelSettings = wheelSettings,
                toggleStates = toggleStates,
                sliderOverrides = sliderOverrides,
                useMph = useMph,
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
