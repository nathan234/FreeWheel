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

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import org.freewheel.core.domain.common.AlarmType
import org.freewheel.core.domain.telemetry.BmsState
import org.freewheel.core.domain.DashboardLabels
import org.freewheel.core.domain.settings.SettingsLabels
import org.freewheel.core.domain.telemetry.SpeedDisplayMode
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.settings.WheelSettings
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.domain.dashboard.DashboardPresets
import org.freewheel.core.domain.identity.wheel.WheelCatalog
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.ui.theme.ZoneColors

/**
 * Stateless dashboard body driven by [DashboardLayout].
 * All inputs are plain parameters — no ViewModel dependency.
 * This enables @Preview, Compose UI tests, and future web rendering.
 */
@Composable
fun DashboardContent(
    layout: DashboardLayout,
    telemetry: TelemetryState,
    identity: WheelIdentity,
    bms: BmsState,
    settings: WheelSettings,
    connectionState: ConnectionState,
    activeAlarms: Set<AlarmType>,
    isDemo: Boolean,
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
    onDisconnect: () -> Unit,
    onEditDashboard: (() -> Unit)? = null,
    rangeEstimateKm: Double? = null,
    showControls: Boolean = true,
    topSpeedOverrideKmh: Double? = null,
    observedMaxKmh: Double = 0.0,
    appVersion: String = "",
    discoveredServices: org.freewheel.core.ble.DiscoveredServices? = null,
    modifier: Modifier = Modifier
) {
    val effectiveLayout = remember(layout, identity.wheelType) {
        layout.filteredFor(identity.wheelType)
    }

    val displaySpeed = DisplayUtils.convertSpeed(telemetry.speedKmh, useMph)
    val displayGpsSpeed = DisplayUtils.convertSpeed(gpsSpeed, useMph)
    val speedUnit = DisplayUtils.speedUnit(useMph)
    val gaugeMaxKmh = remember(identity, topSpeedOverrideKmh, observedMaxKmh) {
        WheelCatalog.resolveTopSpeedKmh(
            userOverrideKmh = topSpeedOverrideKmh,
            wheelType = identity.wheelType,
            identity = identity,
            observedMaxKmh = observedMaxKmh,
        )
    }
    val maxSpeed = DisplayUtils.convertSpeed(gaugeMaxKmh, useMph)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection banner
        ConnectionBanner(connectionState = connectionState)

        // Alarm banner
        AlarmBanner(activeAlarms = activeAlarms)

        // Unrecognized-wheel banner — surfaces when the live wheel doesn't match any catalog entry
        UnknownWheelReportBanner(
            identity = identity,
            observedMaxKmh = observedMaxKmh,
            appVersion = appVersion,
            isConnected = connectionState is ConnectionState.Connected,
            discoveredServices = discoveredServices,
        )

        // Hero metric rendering (null = tiles-only layout, skip hero gauge)
        val heroMetric = effectiveLayout.heroMetric
        val isSpeedHero = heroMetric == DashboardMetric.SPEED ||
            heroMetric == DashboardMetric.GPS_SPEED

        if (heroMetric != null) {
            if (isSpeedHero) {
                // Speed display mode picker (dashboard only)
                if (showControls) {
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
                val heroRawValue = heroMetric.extractValue(telemetry) ?: 0.0
                val heroDisplayValue = DisplayUtils.convertDashboardMetricValue(heroRawValue, heroMetric, useMph, useFahrenheit)
                val heroUnit = DisplayUtils.dashboardMetricUnit(heroMetric, useMph, useFahrenheit)
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
                    val tileMaxOverride = if (
                        metric == DashboardMetric.SPEED || metric == DashboardMetric.GPS_SPEED
                    ) gaugeMaxKmh else null
                    RenderGaugeTile(
                        metric = metric,
                        telemetry = telemetry,
                        gpsSpeed = gpsSpeed,
                        telemetryBuffer = telemetryBuffer,
                        samples = samples,
                        useMph = useMph,
                        useFahrenheit = useFahrenheit,
                        onNavigateToMetric = onNavigateToMetric,
                        onLongPress = onEditDashboard,
                        effectiveMaxOverrideKmh = tileMaxOverride,
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
                    val statMaxOverride = if (
                        metric == DashboardMetric.SPEED || metric == DashboardMetric.GPS_SPEED
                    ) gaugeMaxKmh else null
                    RenderStatRow(
                        metric = metric,
                        telemetry = telemetry,
                        gpsSpeed = gpsSpeed,
                        useMph = useMph,
                        useFahrenheit = useFahrenheit,
                        effectiveMaxOverrideKmh = statMaxOverride
                    )
                }
            }
        }

        // Range estimate (shown when available)
        if (rangeEstimateKm != null) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatRow(
                    label = DashboardLabels.ESTIMATED_RANGE,
                    value = DisplayUtils.formatDistance(rangeEstimateKm, useMph, decimals = 1)
                )
            }
        }

        // BMS summary card (conditional — shown when BMS data available)
        if (effectiveLayout.showBmsSummary && bms.bms1 != null) {
            val bms = bms.bms1!!
            if (bms.cellNum > 0) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { onNavigateToBms() },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                DashboardLabels.BMS_SUMMARY,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                Icons.Default.BatteryFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatRow(
                            label = DashboardLabels.BMS_MIN_CELL,
                            value = DisplayUtils.formatBmsCellLabeled(bms.minCell, bms.minCellNum)
                        )
                        StatRow(
                            label = DashboardLabels.BMS_MAX_CELL,
                            value = DisplayUtils.formatBmsCellLabeled(bms.maxCell, bms.maxCellNum)
                        )
                        val diffColor = if (bms.cellDiff > 0.1) ZoneColors.red
                            else if (bms.cellDiff > 0.05) ZoneColors.orange
                            else Color.Unspecified
                        StatRow(
                            label = DashboardLabels.BMS_CELL_DIFF,
                            value = DisplayUtils.formatBmsCell(bms.cellDiff),
                            valueColor = diffColor
                        )
                    }
                }
            }
        }

        // Wheel settings card (conditional)
        if (effectiveLayout.showWheelSettings && settings.pedalsMode >= 0) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatRow(label = DashboardLabels.PEDALS_MODE, value = DisplayUtils.pedalsModeText(settings.pedalsMode))
                StatRow(label = DashboardLabels.TILT_BACK_SPEED, value = DisplayUtils.tiltBackSpeedText(settings.tiltBackSpeed, useMph))
                if (settings.alertSpeed > 0) {
                    StatRow(label = DashboardLabels.ALERT_SPEED, value = DisplayUtils.alertSpeedText(settings.alertSpeed, useMph))
                }
                if (settings.autoOffTime > 0) {
                    StatRow(label = DashboardLabels.AUTO_OFF_TIME, value = DisplayUtils.autoOffTimeText(settings.autoOffTime))
                }
                StatRow(label = DashboardLabels.LIGHT, value = DisplayUtils.lightModeText(settings.lightMode))
                StatRow(label = DashboardLabels.LED_MODE, value = "${settings.ledMode}")
            }
        }

        // Wheel info card (conditional)
        if (effectiveLayout.showWheelInfo && (identity.name.isNotEmpty() || identity.model.isNotEmpty())) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (identity.name.isNotEmpty()) {
                    StatRow(label = DashboardLabels.NAME, value = identity.name)
                }
                if (identity.model.isNotEmpty()) {
                    StatRow(label = DashboardLabels.MODEL, value = identity.model)
                }
                StatRow(label = DashboardLabels.TYPE, value = identity.wheelType.name)
                if (identity.version.isNotEmpty()) {
                    StatRow(label = DashboardLabels.FIRMWARE, value = identity.version)
                }
            }
        }

        // Everything below is dashboard-only (controls, nav buttons, disconnect)
        if (!showControls) {
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        // Demo mode badge
        if (isDemo) {
            ModeBadge(
                text = DashboardLabels.DEMO_MODE_BADGE,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // Settings, Chart, BMS row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onNavigateToWheelSettings,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(SettingsLabels.TITLE)
            }

            FilledTonalButton(
                onClick = onNavigateToChart,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(DashboardLabels.CHART)
            }

            FilledTonalButton(
                onClick = onNavigateToBms,
                shape = MaterialTheme.shapes.medium,
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
                containerColor = if (isDemo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            ),
            shape = MaterialTheme.shapes.medium,
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
            shape = MaterialTheme.shapes.small,
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

private fun previewTelemetry() = TelemetryState(
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
    currentLimit = 80.0
)

private fun previewIdentity(wheelType: WheelType = WheelType.Unknown) = WheelIdentity(
    wheelType = wheelType,
    name = "Preview",
    model = "Demo Wheel",
    version = "1.2.3"
)

private fun previewSettings() = WheelSettings.Kingsong(
    pedalsMode = 1,
    lightMode = 1,
    ledMode = 3,
    ksTiltbackSpeed = 45
)

private val noOp: () -> Unit = {}
private val noOpString: (String) -> Unit = {}
private val noOpMode: (SpeedDisplayMode) -> Unit = {}

@Composable
private fun PreviewDashboard(layout: DashboardLayout, wheelType: WheelType = WheelType.Unknown) {
    MaterialTheme {
        DashboardContent(
            layout = layout,
            telemetry = previewTelemetry(),
            identity = previewIdentity(wheelType),
            bms = BmsState(),
            settings = previewSettings(),
            connectionState = ConnectionState.Connected("00:00:00:00:00:00", "Preview"),
            activeAlarms = emptySet(),
            isDemo = false,
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
        wheelType = WheelType.LORIN
    )
}
