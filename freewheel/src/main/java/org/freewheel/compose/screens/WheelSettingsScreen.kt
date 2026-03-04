package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.domain.DashboardLabels
import org.freewheel.compose.components.DangerousActionDialog
import org.freewheel.compose.components.SectionCard
import org.freewheel.core.domain.ControlSpec
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.WheelSettingsConfig

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/WheelSettingsView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Top bar with back button
//  2. Dynamic sections from WheelSettingsConfig.sections(wheelType)
//  3. Control rendering: Toggle, Segmented, Picker, Slider, DangerousButton, DangerousToggle
//  4. Confirmation dialogs for dangerous actions (calibrate, power off, lock)
//  5. Empty state when no settings available for wheel type
//  Note: iOS has reusable WheelSettingsContent component embedded in SettingsView;
//        Android has standalone WheelSettingsScreen + SectionCard component

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelSettingsScreen(viewModel: WheelViewModel, onBack: () -> Unit) {
    val wheelState by viewModel.wheelState.collectAsStateWithLifecycle()
    val sections = remember(wheelState.wheelType) {
        WheelSettingsConfig.sections(wheelState.wheelType)
    }

    // Local toggle state for write-only commands
    val toggleStates = remember { mutableStateMapOf<SettingsCommandId, Boolean>() }

    // Persisted slider values for write-only commands (e.g. beeper volume)
    val sliderOverrides = remember(sections) {
        val map = mutableStateMapOf<SettingsCommandId, Int>()
        for (section in sections) {
            for (control in section.controls) {
                if (control is ControlSpec.Slider) {
                    val saved = viewModel.loadSliderValue(control.commandId)
                    if (saved != null) map[control.commandId] = saved
                }
            }
        }
        map
    }

    // Pending confirmation dialog
    var pendingAction by remember { mutableStateOf<ControlSpec?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(DashboardLabels.WHEEL_SETTINGS) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CommonLabels.BACK)
                    }
                },
            )
        }
    ) { contentPadding ->
        if (sections.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    DashboardLabels.WHEEL_SETTINGS_EMPTY,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }

                for (section in sections) {
                    item(key = section.title) {
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

                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        // Confirmation dialog
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
}
