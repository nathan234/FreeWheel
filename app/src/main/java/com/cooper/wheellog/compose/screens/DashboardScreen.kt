package com.cooper.wheellog.compose.screens

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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.compose.WheelViewModel
import com.cooper.wheellog.compose.components.AlarmBanner
import com.cooper.wheellog.compose.components.ConnectionBanner
import com.cooper.wheellog.compose.components.GaugeTile
import com.cooper.wheellog.core.domain.SpeedDisplayMode
import com.cooper.wheellog.compose.components.SpeedGauge
import com.cooper.wheellog.compose.components.StatRow
import com.cooper.wheellog.core.domain.CommonLabels
import com.cooper.wheellog.core.domain.DashboardLabels
import com.cooper.wheellog.core.domain.SettingsLabels
import com.cooper.wheellog.core.domain.PreferenceKeys
import com.cooper.wheellog.core.telemetry.ColorZone
import com.cooper.wheellog.core.telemetry.MetricType
import com.cooper.wheellog.core.utils.DisplayUtils
import java.util.Locale

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/WheelLog/Views/DashboardView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Connection banner (Android only — iOS uses system indicator)
//  2. Alarm banner
//  3. Speed display mode picker (Wheel/GPS/Both)
//  4. Speed gauge (tappable → metric detail)
//  5. 2x3 Gauge Tile Grid: Speed, Battery, Power, PWM, Temp, GPS Speed
//  6. Stats: Voltage, Current, Trip Distance, Total Distance
//  7. Wheel settings (conditional on pedalsMode >= 0)
//  8. Wheel info: Name, Model, Type, Firmware
//  9. Demo mode badge (iOS also has Test mode badge)
// 10. Controls: Horn, Light, Settings (Android) / Horn, Light (iOS)
// 11. Record/Chart/BMS row
// 12. Disconnect button

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: WheelViewModel,
    onNavigateToChart: () -> Unit,
    onNavigateToBms: () -> Unit = {},
    onNavigateToMetric: (String) -> Unit = {},
    onNavigateToWheelSettings: () -> Unit = {}
) {
    val wheelState by viewModel.wheelState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val activeAlarms by viewModel.activeAlarms.collectAsState()
    val isDemo by viewModel.isDemo.collectAsState()
    val isLogging by viewModel.isLogging.collectAsState()
    val isLightOn by viewModel.isLightOn.collectAsState()
    val samples by viewModel.telemetrySamples.collectAsState()
    val gpsSpeed by viewModel.gpsSpeedKmh.collectAsState()
    val useMph = viewModel.getGlobalBool(PreferenceKeys.USE_MPH, false)
    val useFahrenheit = viewModel.getGlobalBool(PreferenceKeys.USE_FAHRENHEIT, false)

    val displaySpeed = DisplayUtils.convertSpeed(wheelState.speedKmh, useMph)
    val displayGpsSpeed = DisplayUtils.convertSpeed(gpsSpeed, useMph)
    val speedUnit = DisplayUtils.speedUnit(useMph)
    val maxSpeed = DisplayUtils.maxSpeedDefault(useMph)

    var speedDisplayMode by remember {
        mutableStateOf(SpeedDisplayMode.entries[viewModel.getGlobalInt(PreferenceKeys.SPEED_DISPLAY_MODE, 0).coerceIn(0, 2)])
    }

    val title = wheelState.displayName

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) }
            )
        }
    ) { contentPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection banner
        ConnectionBanner(connectionState = connectionState)

        // Alarm banner
        AlarmBanner(activeAlarms = activeAlarms)

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
                    onClick = {
                        speedDisplayMode = mode
                        viewModel.setGlobalInt(PreferenceKeys.SPEED_DISPLAY_MODE, mode.ordinal)
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // Speed gauge (tappable — navigates to speed metric chart)
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

        // 2x3 Gauge Tile Grid
        val buffer = viewModel.telemetryBuffer
        val tileModifier = Modifier.weight(1f)

        // Helper to get sparkline data (last 20 points)
        fun sparkline(metric: MetricType): List<Float> {
            val vals = buffer.valuesFor(metric)
            return vals.takeLast(20).map { it.toFloat() }
        }

        // Helper to get color for a metric value
        fun tileColor(metric: MetricType, value: Double): Color {
            val max = buffer.effectiveMax(metric)
            val progress = if (max > 0) (value / max) else 0.0
            return when (metric.colorZone(progress)) {
                ColorZone.GREEN -> Color(0xFF4CAF50)
                ColorZone.ORANGE -> Color(0xFFFF9800)
                ColorZone.RED -> Color(0xFFF44336)
            }
        }

        // Row 1: Speed, Battery
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val speedVal = DisplayUtils.convertSpeed(wheelState.speedKmh, useMph)
            GaugeTile(
                label = MetricType.SPEED.label,
                value = String.format(Locale.US, "%.1f", speedVal),
                unit = speedUnit,
                progress = (speedVal / maxSpeed).toFloat(),
                color = tileColor(MetricType.SPEED, wheelState.speedKmh),
                sparklineData = sparkline(MetricType.SPEED),
                onClick = { onNavigateToMetric("speed") },
                modifier = tileModifier
            )
            val batteryVal = wheelState.batteryLevel.toDouble()
            GaugeTile(
                label = MetricType.BATTERY.label,
                value = "${wheelState.batteryLevel}",
                unit = "%",
                progress = (batteryVal / MetricType.BATTERY.maxValue).toFloat(),
                color = tileColor(MetricType.BATTERY, batteryVal),
                sparklineData = sparkline(MetricType.BATTERY),
                onClick = { onNavigateToMetric("battery") },
                modifier = tileModifier
            )
        }

        // Row 2: Power, PWM
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val powerVal = wheelState.powerW
            val powerMax = buffer.effectiveMax(MetricType.POWER)
            GaugeTile(
                label = MetricType.POWER.label,
                value = String.format(Locale.US, "%.0f", powerVal),
                unit = "W",
                progress = if (powerMax > 0) (kotlin.math.abs(powerVal) / powerMax).toFloat() else 0f,
                color = tileColor(MetricType.POWER, kotlin.math.abs(powerVal)),
                sparklineData = sparkline(MetricType.POWER),
                onClick = { onNavigateToMetric("power") },
                modifier = tileModifier
            )
            val pwmVal = wheelState.pwmPercent
            GaugeTile(
                label = MetricType.PWM.label,
                value = String.format(Locale.US, "%.1f", pwmVal),
                unit = "%",
                progress = (pwmVal / MetricType.PWM.maxValue).toFloat(),
                color = tileColor(MetricType.PWM, pwmVal),
                sparklineData = sparkline(MetricType.PWM),
                onClick = { onNavigateToMetric("pwm") },
                modifier = tileModifier
            )
        }

        // Row 3: Temperature, GPS Speed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val tempC = wheelState.temperatureC.toDouble()
            val tempDisplay = DisplayUtils.convertTemp(tempC, useFahrenheit)
            val tempUnit = DisplayUtils.temperatureUnit(useFahrenheit)
            GaugeTile(
                label = MetricType.TEMPERATURE.label,
                value = String.format(Locale.US, "%.0f", tempDisplay),
                unit = tempUnit,
                progress = (tempC / MetricType.TEMPERATURE.maxValue).toFloat(),
                color = tileColor(MetricType.TEMPERATURE, tempC),
                sparklineData = sparkline(MetricType.TEMPERATURE),
                onClick = { onNavigateToMetric("temperature") },
                modifier = tileModifier
            )
            val gpsVal = DisplayUtils.convertSpeed(gpsSpeed, useMph)
            val gpsDisplay = if (gpsSpeed > 0) String.format(Locale.US, "%.1f", gpsVal) else "\u2014"
            GaugeTile(
                label = MetricType.GPS_SPEED.label,
                value = gpsDisplay,
                unit = speedUnit,
                progress = (gpsVal / maxSpeed).toFloat(),
                color = tileColor(MetricType.GPS_SPEED, gpsSpeed),
                sparklineData = sparkline(MetricType.GPS_SPEED),
                onClick = { onNavigateToMetric("gps_speed") },
                modifier = tileModifier
            )
        }

        // Compact stats row: Voltage, Current, Trip Dist, Total Dist
        StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
            StatRow(label = DashboardLabels.VOLTAGE, value = String.format(Locale.US, "%.1f V", wheelState.voltageV))
            StatRow(label = DashboardLabels.CURRENT, value = String.format(Locale.US, "%.1f A", wheelState.currentA))
            StatRow(label = DashboardLabels.TRIP_DISTANCE, value = DisplayUtils.formatDistance(wheelState.wheelDistanceKm, useMph))
            StatRow(label = DashboardLabels.TOTAL_DISTANCE, value = DisplayUtils.formatDistance(wheelState.totalDistanceKm, useMph, decimals = 1))
        }

        // Wheel settings (only when received)
        if (wheelState.pedalsMode >= 0) {
            StatsSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatRow(label = DashboardLabels.PEDALS_MODE, value = DisplayUtils.pedalsModeText(wheelState.pedalsMode))
                StatRow(label = DashboardLabels.TILT_BACK_SPEED, value = DisplayUtils.tiltBackSpeedText(wheelState.tiltBackSpeed, useMph))
                StatRow(label = DashboardLabels.LIGHT, value = DisplayUtils.lightModeText(wheelState.lightMode))
                StatRow(label = DashboardLabels.LED_MODE, value = "${wheelState.ledMode}")
            }
        }

        // Wheel info
        if (wheelState.name.isNotEmpty() || wheelState.model.isNotEmpty()) {
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

        // Controls row: Horn, Light (hidden in demo mode)
        if (!isDemo) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.wheelBeep() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(DashboardLabels.HORN)
                }
                Button(
                    onClick = { viewModel.toggleLight() },
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
                    onClick = { viewModel.toggleLogging() },
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
            onClick = { viewModel.disconnect() },
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
    } // Scaffold content
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

