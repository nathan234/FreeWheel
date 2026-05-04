package org.freewheel.compose.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.BuildConfig
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.components.StatRow
import org.freewheel.compose.components.WheelSettingsContent
import org.freewheel.core.domain.AlarmAction
import org.freewheel.core.domain.AppSettingId
import org.freewheel.core.domain.AppSettingSpec
import org.freewheel.core.domain.AppSettingVisibilityEvaluator
import org.freewheel.core.domain.AppSettingsConfig
import org.freewheel.core.domain.AppSettingsDestinations
import org.freewheel.core.domain.AppSettingsActions
import org.freewheel.core.domain.AppSettingsSection
import org.freewheel.core.domain.AppSettingsState
import org.freewheel.core.domain.AppSettingsValueIds
import org.freewheel.core.domain.SettingsLabels
import org.freewheel.core.domain.SettingScope
import org.freewheel.core.domain.WheelSettingsConfig
import org.freewheel.core.domain.displayUnit
import org.freewheel.core.domain.displayValue

// Settings screen structure is driven by AppSettingsConfig (KMP shared).
// Both Android and iOS render from the same config to prevent drift.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: WheelViewModel,
    onNavigateToEditNavigation: () -> Unit = {},
    onNavigateToCapture: () -> Unit = {},
    onNavigateToEventLog: () -> Unit = {},
    onNavigateToErrorLog: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {}
) {
    val wheelSettings by viewModel.settingsState.collectAsStateWithLifecycle()
    val identity by viewModel.identityState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // App settings state (for reading/writing prefs and evaluating visibility)
    val boolStates = remember { mutableStateMapOf<AppSettingId, Boolean>() }
    val intStates = remember { mutableStateMapOf<AppSettingId, Int>() }

    // Initialize state from prefs on first composition
    val sections = remember { AppSettingsConfig.sections() }
    remember {
        for (section in sections) {
            for (control in section.controls) {
                val id = control.settingId ?: continue
                when (control) {
                    is AppSettingSpec.Toggle -> boolStates[id] = readBool(id, viewModel)
                    is AppSettingSpec.Slider -> intStates[id] = readInt(id, viewModel)
                    is AppSettingSpec.Picker -> intStates[id] = readInt(id, viewModel)
                    else -> {}
                }
            }
        }
        true // Stable return for remember
    }

    // Build visibility state
    val visibilityState = AppSettingsState(
        boolValues = boolStates.toMap(),
        intValues = intStates.toMap(),
        isConnected = connectionState.isConnected,
        wheelType = identity.wheelType
    )

    val useMph = boolStates[AppSettingId.USE_MPH] ?: AppSettingId.USE_MPH.defaultBool
    val useFahrenheit = boolStates[AppSettingId.USE_FAHRENHEIT] ?: AppSettingId.USE_FAHRENHEIT.defaultBool

    // Wheel settings sections — rendering, toggle/slider state, and dangerous-action
    // confirmation are owned by WheelSettingsContent.
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val wheelSections = remember(identity.wheelType, capabilities) {
        WheelSettingsConfig.sections(identity.wheelType, capabilities)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            SettingsLabels.TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        for (section in sections) {
            if (!AppSettingVisibilityEvaluator.isVisible(section.visibility, visibilityState)) continue

            // Wheel settings placeholder: delegate to shared WheelSettingsContent
            if (section.title == AppSettingsConfig.WHEEL_SETTINGS_TITLE) {
                if (wheelSections.isNotEmpty()) {
                    WheelSettingsContent(
                        viewModel = viewModel,
                        sections = wheelSections,
                        wheelSettings = wheelSettings,
                        useMph = useMph
                    )
                }
                continue
            }

            // Close App section: render as standalone button
            if (section.controls.singleOrNull() is AppSettingSpec.ActionButton) {
                val action = section.controls.single() as AppSettingSpec.ActionButton
                OutlinedButton(
                    onClick = {
                        when (action.actionId) {
                            AppSettingsActions.CLOSE_APP -> {
                                viewModel.shutdownService()
                                (context as? Activity)?.finishAffinity()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = if (action.isDestructive) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(action.label)
                }
                continue
            }

            // Standard section
            val visibleControls = section.controls.filter {
                AppSettingVisibilityEvaluator.isVisible(it.visibility, visibilityState)
            }
            if (visibleControls.isEmpty() && section.footer == null) continue

            SettingsSection(title = section.title) {
                visibleControls.forEachIndexed { index, spec ->
                    if (index > 0) HorizontalDivider()
                    RenderAppControl(
                        spec = spec,
                        viewModel = viewModel,
                        boolStates = boolStates,
                        intStates = intStates,
                        useMph = useMph,
                        useFahrenheit = useFahrenheit,
                        context = context,
                        onNavigate = { destinationId ->
                            when (destinationId) {
                                AppSettingsDestinations.CUSTOMIZE_NAVIGATION -> onNavigateToEditNavigation()
                                AppSettingsDestinations.BLE_CAPTURE -> onNavigateToCapture()
                                AppSettingsDestinations.CONNECTION_ERROR_LOG -> onNavigateToErrorLog()
                                AppSettingsDestinations.WHEEL_EVENT_LOG -> onNavigateToEventLog()
                                AppSettingsDestinations.DIAGNOSTICS -> onNavigateToDiagnostics()
                            }
                        }
                    )
                }
                section.footer?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Generic control renderer
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderAppControl(
    spec: AppSettingSpec,
    viewModel: WheelViewModel,
    boolStates: MutableMap<AppSettingId, Boolean>,
    intStates: MutableMap<AppSettingId, Int>,
    useMph: Boolean,
    useFahrenheit: Boolean,
    context: android.content.Context,
    onNavigate: (String) -> Unit
) {
    when (spec) {
        is AppSettingSpec.Toggle -> {
            val id = spec.settingId
            val checked = boolStates[id] ?: id.defaultBool
            SettingsToggle(
                label = spec.label,
                checked = checked,
                onCheckedChange = {
                    boolStates[id] = it
                    writeBool(id, it, viewModel)
                }
            )
        }

        is AppSettingSpec.Picker -> {
            val id = spec.settingId
            val currentIndex = intStates[id] ?: id.defaultInt
            val selectedLabel = AlarmAction.fromValue(currentIndex).label
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(spec.label)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        spec.options.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    intStates[id] = index
                                    writeInt(id, index, viewModel)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        is AppSettingSpec.Slider -> {
            val id = spec.settingId
            val storedValue = intStates[id] ?: id.defaultInt
            val display = spec.displayValue(storedValue, useMph, useFahrenheit)
            val unitText = spec.displayUnit(useMph, useFahrenheit)
            AlarmSlider(
                label = spec.label,
                value = storedValue.toFloat(),
                range = spec.min.toFloat()..spec.max.toFloat(),
                displayValue = display,
                unit = unitText,
                onValueChange = {
                    val newValue = it.toInt()
                    intStates[id] = newValue
                    writeInt(id, newValue, viewModel)
                }
            )
        }

        is AppSettingSpec.NavLink -> {
            SettingsNavRow(label = spec.label, onClick = { onNavigate(spec.destinationId) })
        }

        is AppSettingSpec.StaticInfo -> {
            val value = when (spec.valueId) {
                AppSettingsValueIds.APP_VERSION -> BuildConfig.VERSION_NAME
                AppSettingsValueIds.BUILD_DATE -> BuildConfig.BUILD_DATE
                else -> ""
            }
            StatRow(label = spec.label, value = value)
        }

        is AppSettingSpec.ExternalLink -> {
            SettingsNavRow(
                label = spec.label,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.url)))
                }
            )
        }

        is AppSettingSpec.ActionButton -> {
            // Handled at section level for standalone rendering
        }
    }
}

// ---------------------------------------------------------------------------
// Pref read/write helpers
// ---------------------------------------------------------------------------

private fun readBool(id: AppSettingId, viewModel: WheelViewModel): Boolean =
    when (id.scope) {
        SettingScope.GLOBAL -> viewModel.getGlobalBool(id.prefKey, id.defaultBool)
        SettingScope.PER_WHEEL -> viewModel.getPerWheelBool(id.prefKey, id.defaultBool)
    }

private fun readInt(id: AppSettingId, viewModel: WheelViewModel): Int =
    when (id.scope) {
        SettingScope.GLOBAL -> viewModel.getGlobalInt(id.prefKey, id.defaultInt)
        SettingScope.PER_WHEEL -> viewModel.getPerWheelInt(id.prefKey, id.defaultInt)
    }

private fun writeBool(id: AppSettingId, value: Boolean, viewModel: WheelViewModel) {
    when (id.scope) {
        SettingScope.GLOBAL -> viewModel.setGlobalBool(id.prefKey, value)
        SettingScope.PER_WHEEL -> viewModel.setPerWheelBool(id.prefKey, value)
    }
}

private fun writeInt(id: AppSettingId, value: Int, viewModel: WheelViewModel) {
    when (id.scope) {
        SettingScope.GLOBAL -> viewModel.setGlobalInt(id.prefKey, value)
        SettingScope.PER_WHEEL -> viewModel.setPerWheelInt(id.prefKey, value)
    }
}

// ---------------------------------------------------------------------------
// Reusable composables
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
