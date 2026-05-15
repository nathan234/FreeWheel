package org.freewheel.compose.screens

import org.freewheel.core.utils.DisplayUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.core.domain.profile.ChargerProfile
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.charger.ChargerState
import org.freewheel.core.service.ConnectionState

@Composable
fun ChargerScreen(viewModel: WheelViewModel) {
    val chargerState by viewModel.chargerState.collectAsStateWithLifecycle()
    val connectionState by viewModel.chargerConnectionState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isChargerScanning.collectAsStateWithLifecycle()
    val discoveredChargers by viewModel.discoveredChargers.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "HW Charger",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (connectionState.isConnected) {
            ConnectedChargerContent(
                chargerState = chargerState,
                onDisconnect = { viewModel.disconnectCharger() },
                onToggleOutput = { viewModel.toggleChargerOutput(it) },
                onSetVoltage = { viewModel.setChargerVoltage(it) },
                onSetCurrent = { viewModel.setChargerCurrent(it) }
            )
        } else {
            DisconnectedChargerContent(
                connectionState = connectionState,
                savedProfiles = viewModel.getSavedChargerProfiles(),
                discoveredChargers = discoveredChargers,
                isScanning = isScanning,
                onScan = { viewModel.scanForChargers() },
                onStopScan = { viewModel.stopChargerScan() },
                onConnect = { address, password ->
                    viewModel.connectCharger(address, password)
                },
                onSaveProfile = { viewModel.saveChargerProfile(it) },
                onDeleteProfile = { viewModel.forgetChargerProfile(it) },
                onDisconnect = { viewModel.disconnectCharger() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectedChargerContent(
    chargerState: ChargerState,
    onDisconnect: () -> Unit,
    onToggleOutput: (Boolean) -> Unit,
    onSetVoltage: (Float) -> Unit,
    onSetCurrent: (Float) -> Unit
) {
    // Output controls
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Output Control", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Output Enabled")
                Switch(
                    checked = chargerState.isOutputEnabled,
                    onCheckedChange = { onToggleOutput(it) }
                )
            }
        }
    }

    // DC Output telemetry
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DC Output", style = MaterialTheme.typography.titleMedium)
            TelemetryRow("Voltage", DisplayUtils.formatChargerVoltage(chargerState.dcVoltage))
            TelemetryRow("Current", DisplayUtils.formatChargerCurrent(chargerState.dcCurrent))
            TelemetryRow("Power", DisplayUtils.formatChargerPower(chargerState.dcPower))
            if (chargerState.isCharging) {
                Text(
                    "Charging",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    // AC Input telemetry
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AC Input", style = MaterialTheme.typography.titleMedium)
            TelemetryRow("Voltage", DisplayUtils.formatChargerVoltage(chargerState.acVoltage))
            TelemetryRow("Current", DisplayUtils.formatChargerCurrent(chargerState.acCurrent))
            TelemetryRow("Power", DisplayUtils.formatChargerPower(chargerState.acPower))
            TelemetryRow("Frequency", DisplayUtils.formatChargerFrequency(chargerState.acFrequency))
        }
    }

    // Efficiency & temperatures
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            TelemetryRow("Efficiency", DisplayUtils.formatChargerEfficiency(chargerState.efficiency))
            TelemetryRow("Temp 1", DisplayUtils.formatChargerTemperature(chargerState.temperature1))
            TelemetryRow("Temp 2", DisplayUtils.formatChargerTemperature(chargerState.temperature2))
            TelemetryRow("Current Limit", DisplayUtils.formatChargerCurrentLimit(chargerState.currentLimitingPoint))
        }
    }

    // Setpoints
    if (chargerState.targetVoltage > 0f || chargerState.targetCurrent > 0f) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Setpoints", style = MaterialTheme.typography.titleMedium)
                TelemetryRow("Target Voltage", DisplayUtils.formatChargerVoltage(chargerState.targetVoltage))
                TelemetryRow("Target Current", DisplayUtils.formatChargerCurrent(chargerState.targetCurrent))
            }
        }
    }

    // Firmware
    if (chargerState.firmwareVersion.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TelemetryRow("Firmware", chargerState.firmwareVersion)
            }
        }
    }

    // Voltage/Current setters
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set Output", style = MaterialTheme.typography.titleMedium)

            var voltageText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = voltageText,
                onValueChange = { voltageText = it },
                label = { Text("Voltage (V)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            voltageText.toFloatOrNull()?.let { onSetVoltage(it) }
                        }
                    ) { Text("Set") }
                }
            )

            var currentText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = currentText,
                onValueChange = { currentText = it },
                label = { Text("Current (A)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            currentText.toFloatOrNull()?.let { onSetCurrent(it) }
                        }
                    ) { Text("Set") }
                }
            )
        }
    }

    OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Disconnect Charger")
    }
}

@Composable
private fun DisconnectedChargerContent(
    connectionState: ConnectionState,
    savedProfiles: List<ChargerProfile>,
    discoveredChargers: List<WheelViewModel.DiscoveredDevice>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (address: String, password: String) -> Unit,
    onSaveProfile: (ChargerProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    // Connection status
    when (connectionState) {
        is ConnectionState.Connecting -> {
            Text("Connecting...", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onDisconnect) { Text("Cancel") }
        }
        is ConnectionState.DiscoveringServices -> {
            Text("Discovering services...", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onDisconnect) { Text("Cancel") }
        }
        is ConnectionState.Failed -> {
            Text(
                "Connection failed: ${connectionState.error}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        is ConnectionState.ConnectionLost -> {
            Text(
                "Connection lost",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        else -> {}
    }

    // Saved chargers
    if (savedProfiles.isNotEmpty()) {
        Text("Saved Chargers", style = MaterialTheme.typography.titleMedium)
        savedProfiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            profile.displayName.ifEmpty { profile.address },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(profile.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConnect(profile.address, profile.password) }) {
                            Text("Connect")
                        }
                        TextButton(onClick = { onDeleteProfile(profile.address) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }

    // Scan for chargers
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Find Chargers", style = MaterialTheme.typography.titleMedium)
        if (isScanning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                OutlinedButton(onClick = onStopScan) { Text("Stop") }
            }
        } else {
            Button(onClick = onScan) { Text("Scan") }
        }
    }

    if (discoveredChargers.isNotEmpty()) {
        var selectedAddress by remember { mutableStateOf<String?>(null) }
        var password by remember { mutableStateOf("") }

        discoveredChargers.forEach { device ->
            val isSelected = selectedAddress == device.address
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { selectedAddress = device.address }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onSaveProfile(
                                        ChargerProfile(
                                            address = device.address,
                                            displayName = device.name,
                                            password = password,
                                            lastConnectedMs = System.currentTimeMillis()
                                        )
                                    )
                                    onConnect(device.address, password)
                                },
                                enabled = password.isNotBlank()
                            ) {
                                Text("Connect & Save")
                            }
                            OutlinedButton(
                                onClick = { onConnect(device.address, password) },
                                enabled = password.isNotBlank()
                            ) {
                                Text("Connect Once")
                            }
                        }
                    }
                }
            }
        }
    } else if (!isScanning) {
        Text(
            "Tap Scan to search for nearby HW chargers",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
