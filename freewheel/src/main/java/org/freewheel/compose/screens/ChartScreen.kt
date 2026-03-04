package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.ChartLabels
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.telemetry.ChartTimeRange
import org.freewheel.core.telemetry.MetricType
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.compose.components.MarkerSeriesInfo
import org.freewheel.compose.components.SeriesInfo
import org.freewheel.compose.components.SPEED_COLOR
import org.freewheel.compose.components.GPS_SPEED_COLOR
import org.freewheel.compose.components.CURRENT_COLOR
import org.freewheel.compose.components.POWER_COLOR
import org.freewheel.compose.components.TEMP_COLOR
import org.freewheel.compose.components.VOLTAGE_COLOR
import org.freewheel.compose.components.ToggleChip
import org.freewheel.compose.components.TimeRangePicker
import org.freewheel.compose.components.VicoLineChart
import org.freewheel.compose.components.rememberChartMarker
import org.freewheel.core.utils.DisplayUtils

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/TelemetryChartView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Time range picker: 5 min / 1 hr / 24 hr
//  2. Metric toggle chips: Speed, GPS, Current, Power, Temperature
//  3. Main telemetry chart (multi-series, overlaid)
//  4. Voltage chart (single series, below main chart)
//  5. Empty state when no samples
//  Note: Android uses Vico charts; iOS uses Swift Charts with gesture annotation overlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    viewModel: WheelViewModel,
    onBack: () -> Unit
) {
    val samples by viewModel.chartSamples.collectAsStateWithLifecycle()
    val selectedRange by viewModel.chartTimeRange.collectAsStateWithLifecycle()
    val useMph = viewModel.appConfig.useMph
    val useFahrenheit = viewModel.appConfig.useFahrenheit

    var showSpeed by remember { mutableStateOf(true) }
    var showGpsSpeed by remember { mutableStateOf(false) }
    var showCurrent by remember { mutableStateOf(true) }
    var showPower by remember { mutableStateOf(false) }
    var showTemperature by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ChartLabels.TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CommonLabels.BACK)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Time range picker
            TimeRangePicker(
                selected = selectedRange,
                onSelect = { viewModel.setChartTimeRange(it) }
            )

            // Toggle chips
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ToggleChip(MetricType.SPEED.label, SPEED_COLOR, showSpeed, { showSpeed = !showSpeed }) }
                item { ToggleChip(ChartLabels.GPS, GPS_SPEED_COLOR, showGpsSpeed, { showGpsSpeed = !showGpsSpeed }) }
                item { ToggleChip(ChartLabels.CURRENT, CURRENT_COLOR, showCurrent, { showCurrent = !showCurrent }) }
                item { ToggleChip(MetricType.POWER.label, POWER_COLOR, showPower, { showPower = !showPower }) }
                item { ToggleChip(MetricType.TEMPERATURE.label, TEMP_COLOR, showTemperature, { showTemperature = !showTemperature }) }
            }

            if (samples.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            ChartLabels.WAITING,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val speedUnit = if (useMph) "mph" else "km/h"
                val tempUnit = if (useFahrenheit) "\u00B0F" else "\u00B0C"

                // Build visible series info
                val visibleSeries = mutableListOf<SeriesInfo>()
                val visibleMarkerInfo = mutableListOf<MarkerSeriesInfo>()
                if (showSpeed) {
                    visibleSeries += SeriesInfo(SPEED_COLOR, samples.map { DisplayUtils.convertSpeed(it.speedKmh, useMph) })
                    visibleMarkerInfo += MarkerSeriesInfo(MetricType.SPEED.label, speedUnit, 1)
                }
                if (showGpsSpeed) {
                    visibleSeries += SeriesInfo(GPS_SPEED_COLOR, samples.map { DisplayUtils.convertSpeed(it.gpsSpeedKmh, useMph) })
                    visibleMarkerInfo += MarkerSeriesInfo(ChartLabels.GPS, speedUnit, 1)
                }
                if (showCurrent) {
                    visibleSeries += SeriesInfo(CURRENT_COLOR, samples.map { it.currentA })
                    visibleMarkerInfo += MarkerSeriesInfo(ChartLabels.CURRENT, "A", 1)
                }
                if (showPower) {
                    visibleSeries += SeriesInfo(POWER_COLOR, samples.map { it.powerW })
                    visibleMarkerInfo += MarkerSeriesInfo(MetricType.POWER.label, "W", 0)
                }
                if (showTemperature) {
                    visibleSeries += SeriesInfo(TEMP_COLOR, samples.map { DisplayUtils.convertTemp(it.temperatureC, useFahrenheit) })
                    visibleMarkerInfo += MarkerSeriesInfo(MetricType.TEMPERATURE.label, tempUnit, 0)
                }

                val timeFormatPattern = if (selectedRange == ChartTimeRange.FIVE_MINUTES) "mm:ss" else "HH:mm"

                if (visibleSeries.isNotEmpty()) {
                    val marker = rememberChartMarker(samples, visibleMarkerInfo, timeFormatPattern)
                    // Main telemetry chart
                    VicoLineChart(
                        samples = samples,
                        seriesList = visibleSeries,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .padding(horizontal = 16.dp),
                        timeFormatPattern = timeFormatPattern,
                        marker = marker,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Voltage chart
                Text(
                    ChartLabels.VOLTAGE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = VOLTAGE_COLOR,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                val voltageMarker = rememberChartMarker(
                    samples, listOf(MarkerSeriesInfo(ChartLabels.VOLTAGE, "V", 1)), timeFormatPattern
                )
                VicoLineChart(
                    samples = samples,
                    seriesList = listOf(
                        SeriesInfo(VOLTAGE_COLOR, samples.map { it.voltageV })
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp),
                    timeFormatPattern = timeFormatPattern,
                    marker = voltageMarker,
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
