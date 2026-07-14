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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.settings.ControlSpec
import org.freewheel.core.domain.settings.LastSentWheelCommand
import org.freewheel.core.domain.settings.SettingsCommandId
import org.freewheel.core.domain.settings.SettingsSection
import org.freewheel.core.domain.settings.WheelSettings
import org.freewheel.core.service.ConnectionState

/**
 * Renders wheel-side settings sections plus the dangerous-action confirmation dialog.
 * Owns local toggle state, last-sent slider history, and pending-action state.
 *
 * Used by the dedicated [org.freewheel.compose.screens.WheelSettingsScreen].
 * iOS has the equivalent in `WheelSettingsContent` within `WheelSettingsView.swift`.
 */
@Composable
fun WheelSettingsContent(
    viewModel: WheelViewModel,
    sections: List<SettingsSection>,
    wheelSettings: WheelSettings,
    useMph: Boolean,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    // Key local state on the connected MAC so reconnecting to a different wheel
    // doesn't leak pending toggle overrides or cached slider values from the
    // previous wheel into the new wheel's UI.
    val activeMac = (connectionState as? ConnectionState.Connected)?.address ?: ""
    val toggleStates = remember(activeMac) { mutableStateMapOf<SettingsCommandId, Boolean>() }
    val lastSentValues = remember(activeMac, sections) {
        mutableStateMapOf<SettingsCommandId, LastSentWheelCommand>().apply {
            for (section in sections) {
                for (control in section.controls) {
                    if (control is ControlSpec.Slider) {
                        viewModel.wheelCommandCacheStore.loadLastSent(control.commandId)
                            ?.let { put(control.commandId, it) }
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
                lastSentValues = lastSentValues,
                useMph = useMph,
                onIntCommand = { id, value ->
                    val sentAtMs = System.currentTimeMillis()
                    viewModel.wheelCommandCacheStore.saveLastSent(id, value, sentAtMs)
                    lastSentValues[id] = LastSentWheelCommand(value, sentAtMs)
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
