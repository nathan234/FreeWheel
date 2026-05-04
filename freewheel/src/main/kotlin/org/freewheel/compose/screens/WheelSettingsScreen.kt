package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.components.WheelSettingsContent
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.domain.DashboardLabels
import org.freewheel.core.domain.WheelSettingsConfig

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/WheelSettingsView.swift.
// Section structure comes from the shared WheelSettingsConfig; the platform-specific
// pieces here are the top bar, the gauge top-speed override card, and the empty-state
// fallback. Section rendering and dangerous-action confirmation live in
// WheelSettingsContent (mirrors iOS's WheelSettingsContent embedded in SettingsView).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelSettingsScreen(viewModel: WheelViewModel, onBack: () -> Unit) {
    val wheelSettings by viewModel.settingsState.collectAsStateWithLifecycle()
    val identity by viewModel.identityState.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val sections = remember(identity.wheelType, capabilities) {
        WheelSettingsConfig.sections(identity.wheelType, capabilities)
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            GaugeTopSpeedOverrideCard(viewModel)

            if (sections.isEmpty()) {
                Text(
                    DashboardLabels.WHEEL_SETTINGS_EMPTY,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                WheelSettingsContent(
                    viewModel = viewModel,
                    sections = sections,
                    wheelSettings = wheelSettings,
                    useMph = viewModel.appConfig.useMph
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * App-side per-wheel override for the speedometer gauge maximum (km/h). Wins over
 * the catalog match and any auto-resolution. Empty / non-positive value clears the override.
 */
@Composable
private fun GaugeTopSpeedOverrideCard(viewModel: WheelViewModel) {
    val current by viewModel.topSpeedOverrideKmh.collectAsStateWithLifecycle()
    var draft by remember(current) { mutableStateOf(current?.let { it.toInt().toString() } ?: "") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Speedometer scale",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Override the gauge maximum for this wheel. Empty = use catalog / auto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { v -> draft = v.filter { it.isDigit() }.take(3) },
                    label = { Text("Top speed (km/h)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val kmh = draft.toIntOrNull()?.toDouble()?.takeIf { it > 0.0 }
                        viewModel.setTopSpeedOverrideForCurrentWheel(kmh)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Save") }
                TextButton(
                    onClick = {
                        draft = ""
                        viewModel.setTopSpeedOverrideForCurrentWheel(null)
                    }
                ) { Text("Reset") }
            }
        }
    }
}
