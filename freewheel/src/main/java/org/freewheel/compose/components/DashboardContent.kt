package org.freewheel.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.DashboardLabels
import org.freewheel.core.domain.SettingsLabels
import org.freewheel.core.domain.SpeedDisplayMode
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.domain.dashboard.DashboardPresets
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils

/**
 * Stateless dashboard body driven by [DashboardLayout].
 * All inputs are plain parameters — no ViewModel dependency.
 * This enables @Preview, Compose UI tests, and future web rendering.
 */
@Composable
fun DashboardContent(
    layout: DashboardLayout,
    wheelState: WheelState,
    connectionState: ConnectionState,
    activeAlarms: Set<AlarmType>,
    isDemo: Boolean,
    isLogging: Boolean,
    isLightOn: Boolean,
    gpsSpeed: Double,
    useMph: Boolean,
    useFahrenheit: Boolean,
    telemetryBuffer: TelemetryBuffer,
    samples: List<TelemetrySample>,
    speedDisplayMode: SpeedDisplayMode,
    onSpeedDisplayModeChange: (SpeedDisplayMode) -> Unit,
    onNavigateToChart: () -> Unit,
    onNavigateToBms: () -> Unit,
    onNavigateToMetric: (String) -> Unit,
    onNavigateToWheelSettings: () -> Unit,
    onNavigateToEditDashboard: () -> Unit,
    onBeep: () -> Unit,
    onToggleLight: () -> Unit,
    onToggleLogging: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveLayout = remember(layout, wheelState.wheelType) {
        layout.filteredFor(wheelState.wheelType)
    }

    val displaySpeed = DisplayUtils.convertSpeed(wheelState.speedKmh, useMph)
    val displayGpsSpeed = DisplayUtils.convertSpeed(gpsSpeed, useMph)
    val speedUnit = DisplayUtils.speedUnit(useMph)
    val maxSpeed = DisplayUtils.maxSpeedDefault(useMph)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection banner
        ConnectionBanner(connectionState = connectionState)

        // Alarm banner
        AlarmBanner(activeAlarms = activeAlarms)

        // Hero metric rendering
        val isSpeedHero = effectiveLayout.heroMetric == DashboardMetric.SPEED ||
            effectiveLayout.heroMetric == DashboardMetric.GPS_SPEED

        if (isSpeedHero) {
            // Speed display mode picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                for (mode in SpeedDisplayMode.entries) {
                    val label = when (mode) {
                        SpeedDisplayMode.WHEEL -> DashboardLabels.SPEED_SOURCE_SPEED
                        SpeedDisplayMode.GPS -> DashboardLabels.SPEED_SOURCE_GPS
                        SpeedDisplayMode.BOTH -> DashboardLabels.SPEED_SOURCE_BOTH
                    }
                    FilterChip(
                        selected = speedDisplayMode == mode,
                        onClick = { onSpeedDisplayModeChange(mode) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            // Speed gauge (hero metric)
            SpeedGauge(
                speed = displaySpeed,
                maxSpeed = maxSpeed,
                unitLabel = speedUnit,
                gpsSpeed = displayGpsSpeed,
                mode = speedDisplayMode,
                modifier = Modifier
                    .height(250.dp)
                    .padding(top = 8.dp)
                    .clickable { onNavigateToMetric("speed") }
            )
        } else {
            // Generic hero gauge for non-speed metrics
            val heroMetric = effectiveLayout.heroMetric
            val heroRawValue = heroMetric.extractValue(wheelState) ?: 0.0
            val heroDisplayValue = DisplayUtils.convertMetricValue(heroRawValue, heroMetric, useMph, useFahrenheit)
            val heroUnit = DisplayUtils.metricUnit(heroMetric, useMph, useFahrenheit)
            val heroMax = heroMetric.maxValue.let { if (it > 0) it else kotlin.math.abs(heroRawValue).coerceAtLeast(1.0) }

            HeroGauge(
                value = heroDisplayValue,
                maxValue = heroMax,
                unitLabel = heroUnit,
                label = heroMetric.label,
                metric = heroMetric,
                modifier = Modifier
                    .height(250.dp)
                    .padding(top = 8.dp)
                    .clickable { onNavigateToMetric(heroMetric.name.lowercase()) }
            )
        }

        // Gauge tile grid — 2 columns, driven by layout
        val tileModifier = Modifier.weight(1f)
        effectiveLayout.tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (metric in row) {
                    RenderGaugeTile(
                        metric = metric,
                        wheelState = wheelState,
                        gpsSpeed = gpsSpeed,
                        telemetryBuffer = telemetryBuffer,
                        samples = samples,
                        useMph = useMph,
                        useFahrenheit = useFahrenheit,
                        onNavigateToMetric = onNavigateToMetric,
                        modifier = tileModifier
                    )
                }
                // Pad odd rows with a spacer
                if (row.size == 1) {
                    Spacer(modifier = tileModifier)
                }
            }
        }

        // Stats section — driven by layout
        if (effectiveLayout.stats.isNotEmpty()) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                for (metric in effectiveLayout.stats) {
                    RenderStatRow(
                        metric = metric,
                        wheelState = wheelState,
                        gpsSpeed = gpsSpeed,
                        useMph = useMph,
                        useFahrenheit = useFahrenheit
                    )
                }
            }
        }

        // Wheel settings card (conditional)
        if (effectiveLayout.showWheelSettings && wheelState.pedalsMode >= 0) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatRow(label = DashboardLabels.PEDALS_MODE, value = DisplayUtils.pedalsModeText(wheelState.pedalsMode))
                StatRow(label = DashboardLabels.TILT_BACK_SPEED, value = DisplayUtils.tiltBackSpeedText(wheelState.tiltBackSpeed, useMph))
                StatRow(label = DashboardLabels.LIGHT, value = DisplayUtils.lightModeText(wheelState.lightMode))
                StatRow(label = DashboardLabels.LED_MODE, value = "${wheelState.ledMode}")
            }
        }

        // Wheel info card (conditional)
        if (effectiveLayout.showWheelInfo && (wheelState.name.isNotEmpty() || wheelState.model.isNotEmpty())) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (wheelState.name.isNotEmpty()) {
                    StatRow(label = DashboardLabels.NAME, value = wheelState.name)
                }
                if (wheelState.model.isNotEmpty()) {
                    StatRow(label = DashboardLabels.MODEL, value = wheelState.model)
                }
                StatRow(label = DashboardLabels.TYPE, value = wheelState.wheelType.name)
                if (wheelState.version.isNotEmpty()) {
                    StatRow(label = DashboardLabels.FIRMWARE, value = wheelState.version)
                }
            }
        }

        // Demo mode badge
        if (isDemo) {
            ModeBadge(
                text = DashboardLabels.DEMO_MODE_BADGE,
                color = Color(0xFFFF9800)
            )
        }

        // Controls row: Horn, Light, Settings (hidden in demo mode)
        if (!isDemo) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onBeep,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(DashboardLabels.HORN)
                }
                Button(
                    onClick = onToggleLight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLightOn) Color(0xFFFFC107) else Color(0xFF2196F3)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isLightOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(DashboardLabels.LIGHT)
                }
                Button(
                    onClick = onNavigateToWheelSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(SettingsLabels.TITLE)
                }
            }
        }

        // Record, Chart, BMS row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionState.isConnected) {
                Button(
                    onClick = onToggleLogging,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLogging) Color(0xFFF44336) else Color.Gray
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isLogging) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isLogging) DashboardLabels.STOP else DashboardLabels.RECORD)
                }
            }

            Button(
                onClick = onNavigateToChart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(DashboardLabels.CHART)
            }

            Button(
                onClick = onNavigateToBms,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.BatteryFull, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(DashboardLabels.BMS)
            }
        }

        // Disconnect button
        Button(
            onClick = onDisconnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDemo) Color(0xFFFF9800) else Color(0xFFF44336)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = if (isDemo) DashboardLabels.STOP_DEMO else DashboardLabels.DISCONNECT,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatsSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun ModeBadge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = 0.15f)
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.height(16.dp)
                )
                Text(
                    text = text,
                    color = color,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// MARK: - Preview Helpers

private fun previewWheelState(wheelType: WheelType = WheelType.Unknown) = WheelState(
    speed = 2200,          // 22 km/h
    voltage = 8400,        // 84V
    current = 1500,        // 15A
    phaseCurrent = 2500,   // 25A
    power = 126000,        // 1260W
    temperature = 3500,    // 35°C
    temperature2 = 4000,   // 40°C
    batteryLevel = 72,
    totalDistance = 1523500,
    wheelDistance = 12340,
    calculatedPwm = 0.44,
    angle = 2.5,
    roll = 1.2,
    torque = 18.5,
    motorPower = 850.0,
    cpuTemp = 42,
    imuTemp = 38,
    cpuLoad = 65,
    speedLimit = 45.0,
    currentLimit = 80.0,
    wheelType = wheelType,
    name = "Preview",
    model = "Demo Wheel",
    version = "1.2.3",
    pedalsMode = 1,
    lightMode = 1,
    ledMode = 3,
    tiltBackSpeed = 45
)

private val noOp: () -> Unit = {}
private val noOpString: (String) -> Unit = {}
private val noOpMode: (SpeedDisplayMode) -> Unit = {}

@Composable
private fun PreviewDashboard(layout: DashboardLayout, wheelType: WheelType = WheelType.Unknown) {
    MaterialTheme {
        DashboardContent(
            layout = layout,
            wheelState = previewWheelState(wheelType),
            connectionState = ConnectionState.Connected("00:00:00:00:00:00", "Preview"),
            activeAlarms = emptySet(),
            isDemo = false,
            isLogging = false,
            isLightOn = false,
            gpsSpeed = 21.5,
            useMph = false,
            useFahrenheit = false,
            telemetryBuffer = TelemetryBuffer(),
            samples = emptyList(),
            speedDisplayMode = SpeedDisplayMode.WHEEL,
            onSpeedDisplayModeChange = noOpMode,
            onNavigateToChart = noOp,
            onNavigateToBms = noOp,
            onNavigateToMetric = noOpString,
            onNavigateToWheelSettings = noOp,
            onNavigateToEditDashboard = noOp,
            onBeep = noOp,
            onToggleLight = noOp,
            onToggleLogging = noOp,
            onDisconnect = noOp
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800, name = "Default Layout")
@Composable
private fun PreviewDefaultLayout() {
    PreviewDashboard(layout = DashboardPresets.default().layout)
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800, name = "Racing Layout")
@Composable
private fun PreviewRacingLayout() {
    PreviewDashboard(layout = DashboardPresets.all().first { it.id == "racing" }.layout)
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800, name = "Compact Layout")
@Composable
private fun PreviewCompactLayout() {
    PreviewDashboard(layout = DashboardPresets.all().first { it.id == "compact" }.layout)
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800, name = "Diagnostic Layout")
@Composable
private fun PreviewDiagnosticLayout() {
    PreviewDashboard(
        layout = DashboardPresets.all().first { it.id == "diagnostic" }.layout,
        wheelType = WheelType.INMOTION_V2
    )
}
