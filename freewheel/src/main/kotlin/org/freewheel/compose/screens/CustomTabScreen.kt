package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.components.DashboardContent
import org.freewheel.core.domain.AppSettingId
import org.freewheel.core.domain.SpeedDisplayMode
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.NavigationTab

/**
 * Renders a custom tab using [DashboardContent] with controls hidden.
 * Shows placeholder when disconnected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTabScreen(
    viewModel: WheelViewModel,
    tabId: String,
    onNavigateToMetric: (String) -> Unit = {},
    onNavigateToEditLayout: () -> Unit = {}
) {
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val identity by viewModel.identityState.collectAsStateWithLifecycle()
    val bms by viewModel.bmsState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val activeAlarms by viewModel.activeAlarms.collectAsStateWithLifecycle()
    val customTabLayouts by viewModel.customTabLayouts.collectAsStateWithLifecycle()
    val navigationConfig by viewModel.navigationConfig.collectAsStateWithLifecycle()
    val isDemo by viewModel.isDemo.collectAsStateWithLifecycle()
    val samples by viewModel.telemetrySamples.collectAsStateWithLifecycle()
    val gpsSpeed by viewModel.gpsSpeedKmh.collectAsStateWithLifecycle()
    val topSpeedOverrideKmh by viewModel.topSpeedOverrideKmh.collectAsStateWithLifecycle()
    val observedMaxKmh by viewModel.observedMaxKmhInSession.collectAsStateWithLifecycle()

    val layout = customTabLayouts[tabId] ?: DashboardLayout.default()
    val tabLabel = navigationConfig.tabs
        .filterIsInstance<NavigationTab.Custom>()
        .firstOrNull { it.id == tabId }
        ?.label ?: "Custom"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(tabLabel) },
                actions = {
                    IconButton(onClick = onNavigateToEditLayout) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Layout")
                    }
                }
            )
        }
    ) { contentPadding ->
        if (!connectionState.isConnected && !isDemo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Connect to a wheel to see data",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            DashboardContent(
                layout = layout,
                telemetry = telemetry,
                identity = identity,
                bms = bms,
                settings = settings,
                connectionState = connectionState,
                activeAlarms = activeAlarms,
                isDemo = isDemo,
                gpsSpeed = gpsSpeed,
                useMph = viewModel.appSettingsStore.getBool(AppSettingId.USE_MPH),
                useFahrenheit = viewModel.appSettingsStore.getBool(AppSettingId.USE_FAHRENHEIT),
                telemetryBuffer = viewModel.telemetryBuffer,
                samples = samples,
                speedDisplayMode = SpeedDisplayMode.WHEEL,
                onSpeedDisplayModeChange = {},
                onNavigateToChart = {},
                onNavigateToBms = {},
                onNavigateToMetric = onNavigateToMetric,
                onNavigateToWheelSettings = {},
                onDisconnect = {},
                showControls = false,
                topSpeedOverrideKmh = topSpeedOverrideKmh,
                observedMaxKmh = observedMaxKmh,
                appVersion = org.freewheel.BuildConfig.VERSION_NAME,
                modifier = Modifier.padding(contentPadding)
            )
        }
    }
}
