package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.ChartLabels
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.domain.RidesLabels
import org.freewheel.compose.components.MarkerSeriesInfo
import org.freewheel.compose.components.SeriesInfo
import org.freewheel.compose.components.SPEED_COLOR
import org.freewheel.compose.components.GPS_SPEED_COLOR
import org.freewheel.compose.components.CURRENT_COLOR
import org.freewheel.compose.components.POWER_COLOR
import org.freewheel.compose.components.TEMP_COLOR
import org.freewheel.compose.components.PWM_COLOR
import org.freewheel.compose.components.VOLTAGE_COLOR
import org.freewheel.compose.components.ToggleChip
import org.freewheel.compose.components.VicoLineChart
import org.freewheel.compose.components.rememberChartMarker
import org.freewheel.core.telemetry.MetricType
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.data.TripDataDbEntry
import org.freewheel.core.logging.CsvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private sealed class TripDetailState {
    data object Loading : TripDetailState()
    data class Error(val message: String) : TripDetailState()
    data class Loaded(
        val trip: TripDataDbEntry?,
        val samples: List<TelemetrySample>
    ) : TripDetailState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    viewModel: WheelViewModel,
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val useMph = viewModel.appConfig.useMph
    val useFahrenheit = viewModel.appConfig.useFahrenheit

    var state by remember { mutableStateOf<TripDetailState>(TripDetailState.Loading) }

    var showSpeed by remember { mutableStateOf(true) }
    var showGpsSpeed by remember { mutableStateOf(false) }
    var showCurrent by remember { mutableStateOf(true) }
    var showPower by remember { mutableStateOf(false) }
    var showTemperature by remember { mutableStateOf(false) }
    var showPwm by remember { mutableStateOf(false) }

    LaunchedEffect(fileName) {
        state = TripDetailState.Loading
        state = withContext(Dispatchers.IO) {
            try {
                val trip = viewModel.loadTripByFileName(fileName)
                val ridesDir = File(context.getExternalFilesDir(null), "rides")
                val csvFile = File(ridesDir, fileName)
                if (!csvFile.exists()) {
                    TripDetailState.Error("CSV file not found")
                } else {
                    val samples = CsvParser.parse(csvFile.readText())
                    if (samples.isEmpty()) {
                        TripDetailState.Error("No data in CSV file")
                    } else {
                        TripDetailState.Loaded(trip, samples)
                    }
                }
            } catch (e: Exception) {
                TripDetailState.Error("Failed to parse ride: ${e.message}")
            }
        }
    }

    // Format title from trip date
    val titleDate = remember(fileName) {
        // fileName format: WheelLog_yyyy_MM_dd_HH_mm_ss.csv
        try {
            val nameWithoutExt = fileName.removeSuffix(".csv").removePrefix("WheelLog_")
            val fmt = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            val date = fmt.parse(nameWithoutExt)
            if (date != null) {
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
            } else fileName
        } catch (_: Exception) {
            fileName
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleDate) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CommonLabels.BACK)
                    }
                },
            )
        }
    ) { padding ->
        when (val s = state) {
            is TripDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is TripDetailState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is TripDetailState.Loaded -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Summary card
                    TripSummaryCard(
                        trip = s.trip,
                        samples = s.samples,
                        useMph = useMph,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                        item { ToggleChip(MetricType.PWM.label, PWM_COLOR, showPwm, { showPwm = !showPwm }) }
                    }

                    val speedUnit = if (useMph) "mph" else "km/h"
                    val tempUnit = if (useFahrenheit) "\u00B0F" else "\u00B0C"

                    // Build visible series
                    val visibleSeries = mutableListOf<SeriesInfo>()
                    val visibleMarkerInfo = mutableListOf<MarkerSeriesInfo>()
                    if (showSpeed) {
                        visibleSeries += SeriesInfo(SPEED_COLOR, s.samples.map { DisplayUtils.convertMetricValue(it.speedKmh, MetricType.SPEED, useMph, useFahrenheit) })
                        visibleMarkerInfo += MarkerSeriesInfo(MetricType.SPEED.label, speedUnit, 1)
                    }
                    if (showGpsSpeed) {
                        visibleSeries += SeriesInfo(GPS_SPEED_COLOR, s.samples.map { DisplayUtils.convertSpeed(it.gpsSpeedKmh, useMph) })
                        visibleMarkerInfo += MarkerSeriesInfo(ChartLabels.GPS, speedUnit, 1)
                    }
                    if (showCurrent) {
                        visibleSeries += SeriesInfo(CURRENT_COLOR, s.samples.map { it.currentA })
                        visibleMarkerInfo += MarkerSeriesInfo(ChartLabels.CURRENT, "A", 1)
                    }
                    if (showPower) {
                        visibleSeries += SeriesInfo(POWER_COLOR, s.samples.map { it.powerW })
                        visibleMarkerInfo += MarkerSeriesInfo(MetricType.POWER.label, "W", 0)
                    }
                    if (showTemperature) {
                        visibleSeries += SeriesInfo(TEMP_COLOR, s.samples.map { DisplayUtils.convertMetricValue(it.temperatureC, MetricType.TEMPERATURE, useMph, useFahrenheit) })
                        visibleMarkerInfo += MarkerSeriesInfo(MetricType.TEMPERATURE.label, tempUnit, 0)
                    }
                    if (showPwm) {
                        visibleSeries += SeriesInfo(PWM_COLOR, s.samples.map { it.pwmPercent })
                        visibleMarkerInfo += MarkerSeriesInfo(MetricType.PWM.label, "%", 1)
                    }

                    val timeFormatPattern = "HH:mm"

                    if (visibleSeries.isNotEmpty()) {
                        val marker = rememberChartMarker(s.samples, visibleMarkerInfo, timeFormatPattern)
                        VicoLineChart(
                            samples = s.samples,
                            seriesList = visibleSeries,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .padding(horizontal = 16.dp),
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
                        s.samples, listOf(MarkerSeriesInfo(ChartLabels.VOLTAGE, "V", 1)), timeFormatPattern
                    )
                    VicoLineChart(
                        samples = s.samples,
                        seriesList = listOf(
                            SeriesInfo(VOLTAGE_COLOR, s.samples.map { it.voltageV })
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp),
                        marker = voltageMarker,
                    )

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TripSummaryCard(
    trip: TripDataDbEntry?,
    samples: List<TelemetrySample>,
    useMph: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (trip != null) {
                // Use DB summary data
                val durationStr = DisplayUtils.formatDurationShort(trip.duration)
                val distStr = DisplayUtils.formatDistance(trip.distance / 1000.0, useMph)
                val maxSpeedStr = DisplayUtils.formatSpeed(trip.maxSpeed.toDouble(), useMph)
                val avgSpeedStr = DisplayUtils.formatSpeed(trip.avgSpeed.toDouble(), useMph)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryItem(RidesLabels.DURATION, durationStr)
                    SummaryItem(RidesLabels.DISTANCE, distStr)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryItem(RidesLabels.MAX_SPEED, maxSpeedStr)
                    SummaryItem(RidesLabels.AVG_SPEED, avgSpeedStr)
                }
                if (trip.maxPower > 0 || trip.consumptionByKm > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (trip.maxPower > 0) {
                            SummaryItem(RidesLabels.MAX_POWER, "${trip.maxPower.toInt()} W")
                        }
                        if (trip.consumptionByKm > 0) {
                            SummaryItem(RidesLabels.ENERGY, DisplayUtils.formatEnergyConsumption(trip.consumptionByKm.toDouble(), useMph))
                        }
                    }
                }
            } else {
                // Fallback: compute from samples
                val stats = TelemetrySample.computeTripStats(samples)
                if (stats != null) {
                    val durationMin = (stats.durationMs / 60000).toInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SummaryItem(RidesLabels.DURATION, DisplayUtils.formatDurationShort(durationMin))
                        SummaryItem(RidesLabels.MAX_SPEED, DisplayUtils.formatSpeed(stats.maxSpeedKmh, useMph))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SummaryItem(RidesLabels.AVG_SPEED, DisplayUtils.formatSpeed(stats.avgSpeedKmh, useMph))
                        SummaryItem(RidesLabels.MAX_POWER, "${stats.maxPowerW.toInt()} W")
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

