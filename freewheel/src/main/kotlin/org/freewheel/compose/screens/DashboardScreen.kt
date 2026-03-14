package org.freewheel.compose.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.components.DashboardContent
import org.freewheel.core.domain.SpeedDisplayMode
import org.freewheel.core.domain.PreferenceKeys

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/DashboardView.swift.
// Layout is now driven by DashboardLayout (configurable per-wheel).
// Fixed sections remain unchanged: alarm banner, speed picker, controls, record/chart/BMS, disconnect.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: WheelViewModel,
    onNavigateToChart: () -> Unit,
    onNavigateToBms: () -> Unit = {},
    onNavigateToMetric: (String) -> Unit = {},
    onNavigateToWheelSettings: () -> Unit = {},
    onNavigateToEditDashboard: () -> Unit = {}
) {
    val wheelState by viewModel.wheelState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val activeAlarms by viewModel.activeAlarms.collectAsStateWithLifecycle()
    val isDemo by viewModel.isDemo.collectAsStateWithLifecycle()
    val isLogging by viewModel.isLogging.collectAsStateWithLifecycle()
    val isLightOn by viewModel.isLightOn.collectAsStateWithLifecycle()
    val samples by viewModel.telemetrySamples.collectAsStateWithLifecycle()
    val gpsSpeed by viewModel.gpsSpeedKmh.collectAsStateWithLifecycle()
    val dashboardLayout by viewModel.dashboardLayout.collectAsStateWithLifecycle()
    val rangeEstimateKm by viewModel.rangeEstimateKm.collectAsStateWithLifecycle()
    val useMph = viewModel.getGlobalBool(PreferenceKeys.USE_MPH, false)
    val useFahrenheit = viewModel.getGlobalBool(PreferenceKeys.USE_FAHRENHEIT, false)

    var speedDisplayMode by remember {
        mutableStateOf(SpeedDisplayMode.entries[viewModel.getGlobalInt(PreferenceKeys.SPEED_DISPLAY_MODE, 0).coerceIn(0, 2)])
    }

    val title = wheelState.displayName

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                actions = {
                    if (connectionState.isConnected && !isDemo) {
                        IconButton(onClick = { viewModel.wheelBeep() }) {
                            Icon(Icons.Default.Campaign, contentDescription = "Horn")
                        }
                        IconButton(onClick = { viewModel.toggleLight() }) {
                            Icon(
                                if (isLightOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Light",
                                tint = if (isLightOn) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (connectionState.isConnected) {
                        IconButton(onClick = { viewModel.toggleLogging() }) {
                            Icon(
                                if (isLogging) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                contentDescription = if (isLogging) "Stop Recording" else "Record",
                                tint = if (isLogging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToEditDashboard) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Dashboard")
                    }
                }
            )
        }
    ) { contentPadding ->
        DashboardContent(
            layout = dashboardLayout,
            wheelState = wheelState,
            connectionState = connectionState,
            activeAlarms = activeAlarms,
            isDemo = isDemo,
            gpsSpeed = gpsSpeed,
            useMph = useMph,
            useFahrenheit = useFahrenheit,
            telemetryBuffer = viewModel.telemetryBuffer,
            samples = samples,
            speedDisplayMode = speedDisplayMode,
            onSpeedDisplayModeChange = { mode ->
                speedDisplayMode = mode
                viewModel.setGlobalInt(PreferenceKeys.SPEED_DISPLAY_MODE, mode.ordinal)
            },
            onNavigateToChart = onNavigateToChart,
            onNavigateToBms = onNavigateToBms,
            onNavigateToMetric = onNavigateToMetric,
            onNavigateToWheelSettings = onNavigateToWheelSettings,
            onDisconnect = { viewModel.disconnect() },
            onEditDashboard = onNavigateToEditDashboard,
            rangeEstimateKm = rangeEstimateKm,
            modifier = Modifier.padding(contentPadding)
        )
    }
}
