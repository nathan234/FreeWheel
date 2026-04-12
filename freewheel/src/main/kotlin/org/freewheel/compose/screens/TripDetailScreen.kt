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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import org.freewheel.compose.components.CsvReplayController
import org.freewheel.compose.components.ReplayStatsPanel
import org.freewheel.compose.components.RideReplayControls
import org.freewheel.compose.components.RideStatsHeader
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freewheel.compose.components.RideMapView
import org.freewheel.compose.components.VicoLineChart
import org.freewheel.compose.components.rememberChartMarker
import org.freewheel.core.logging.RoutePoint
import org.freewheel.core.telemetry.ChartDataPrep
import org.freewheel.core.telemetry.MetricType
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.data.TripDataDbEntry
import org.freewheel.core.logging.CsvParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class TripDetailState {
    data object Loading : TripDetailState()
    data class Error(val message: String) : TripDetailState()
    data class Loaded(
        val trip: TripDataDbEntry?,
        val samples: List<TelemetrySample>,
        val routePoints: List<RoutePoint> = emptyList()
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
    val scope = rememberCoroutineScope()
    val useMph = viewModel.appConfig.useMph
    val useFahrenheit = viewModel.appConfig.useFahrenheit

    var state by remember { mutableStateOf<TripDetailState>(TripDetailState.Loading) }

    var replayController by remember { mutableStateOf<CsvReplayController?>(null) }
    val isReplaying = replayController != null

    // Split mode state
    var isSplitMode by remember { mutableStateOf(false) }
    var splitSliderPosition by remember { mutableFloatStateOf(0.5f) }
    var showSplitConfirm by remember { mutableStateOf(false) }

    var showSpeed by remember { mutableStateOf(true) }
    var showGpsSpeed by remember { mutableStateOf(false) }
    var showCurrent by remember { mutableStateOf(true) }
    var showPower by remember { mutableStateOf(false) }
    var showTemperature by remember { mutableStateOf(false) }
    var showPwm by remember { mutableStateOf(false) }
    var showVoltage by remember { mutableStateOf(false) }

    // Map ↔ chart selection state
    var mapSelectedPoint by remember { mutableStateOf<RoutePoint?>(null) }

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
                    val csvContent = csvFile.readText()
                    val samples = CsvParser.parse(csvContent)
                    if (samples.isEmpty()) {
                        TripDetailState.Error("No data in CSV file")
                    } else {
                        val routePoints = CsvParser.parseRoute(csvContent)
                        TripDetailState.Loaded(trip, samples, routePoints)
                    }
                }
            } catch (e: Exception) {
                TripDetailState.Error("Failed to parse ride: ${e.message}")
            }
        }
    }

    // Compute split timestamp from slider position
    val splitTimestampMs: Long? = remember(state, splitSliderPosition) {
        val s = state as? TripDetailState.Loaded ?: return@remember null
        if (s.samples.size < 2) return@remember null
        val firstTs = s.samples.first().timestampMs
        val lastTs = s.samples.last().timestampMs
        val range = lastTs - firstTs
        // Clamp slider so both halves have at least 1 sample
        val clampedPos = splitSliderPosition.coerceIn(0.01f, 0.99f)
        firstTs + (range * clampedPos).toLong()
    }

    // Format title from trip date
    val titleDate = remember(fileName) {
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

    // Split confirmation dialog
    if (showSplitConfirm && splitTimestampMs != null) {
        val splitTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(splitTimestampMs))
        AlertDialog(
            onDismissRequest = { showSplitConfirm = false },
            title = { Text(RidesLabels.SPLIT_CONFIRM_TITLE) },
            text = { Text("${RidesLabels.SPLIT_CONFIRM_MESSAGE}\n\nSplit at $splitTimeStr") },
            confirmButton = {
                TextButton(onClick = {
                    showSplitConfirm = false
                    val s = state as? TripDetailState.Loaded ?: return@TextButton
                    val trip = s.trip ?: return@TextButton
                    scope.launch {
                        if (viewModel.splitRide(trip, splitTimestampMs, context)) {
                            onBack()
                        }
                    }
                }) {
                    Text(RidesLabels.SPLIT_HERE, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitConfirm = false }) {
                    Text(CommonLabels.CANCEL)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isSplitMode -> RidesLabels.SPLIT_RIDE
                            isReplaying -> RidesLabels.REPLAY
                            else -> titleDate
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            isSplitMode -> {
                                isSplitMode = false
                                splitSliderPosition = 0.5f
                            }
                            isReplaying -> {
                                replayController?.stop()
                                replayController = null
                            }
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            if (isSplitMode || isReplaying) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = when {
                                isSplitMode -> CommonLabels.CANCEL
                                isReplaying -> RidesLabels.EXIT_REPLAY
                                else -> CommonLabels.BACK
                            }
                        )
                    }
                },
                actions = {
                    if (!isReplaying && !isSplitMode && state is TripDetailState.Loaded) {
                        val s = state as TripDetailState.Loaded
                        // Split button
                        if (s.samples.size >= 2 && s.trip != null) {
                            IconButton(onClick = { isSplitMode = true }) {
                                Icon(Icons.Default.ContentCut, contentDescription = RidesLabels.SPLIT_RIDE)
                            }
                        }
                        // Replay button
                        IconButton(onClick = {
                            replayController = CsvReplayController(s.samples)
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = RidesLabels.REPLAY)
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isSplitMode && splitTimestampMs != null) {
                val splitTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(splitTimestampMs))
                BottomAppBar {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            "${RidesLabels.SPLIT_AT} $splitTimeStr",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Slider(
                            value = splitSliderPosition,
                            onValueChange = { splitSliderPosition = it },
                            valueRange = 0.01f..0.99f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(onClick = { showSplitConfirm = true }) {
                                Text(RidesLabels.SPLIT_HERE)
                            }
                        }
                    }
                }
            }
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
                val rc = replayController

                // Max values come from the loaded samples so they stay accurate for rides
                // that were split (trip DB maxes don't get recomputed) or imported from
                // CSVs predating the max-PWM column. Time/distance still come from the
                // trip DB when present so live-recorded durations aren't recomputed.
                val tripStats = remember(s.samples) { ChartDataPrep.computeTripStats(s.samples) }
                val headerStartTimeMs: Long
                val headerEndTimeMs: Long
                val headerDurationSec: Int
                val headerDistanceKm: Double
                val headerMaxSpeedKmh = tripStats?.maxSpeedKmh ?: s.samples.firstOrNull()?.speedKmh ?: 0.0
                val headerMaxPwmPercent = tripStats?.maxPwmPercent
                if (s.trip != null) {
                    headerStartTimeMs = s.trip.start.toLong() * 1000L
                    headerDurationSec = s.trip.duration * 60
                    headerEndTimeMs = headerStartTimeMs + headerDurationSec * 1000L
                    headerDistanceKm = s.trip.distance / 1000.0
                } else {
                    headerStartTimeMs = s.samples.first().timestampMs
                    headerEndTimeMs = s.samples.last().timestampMs
                    headerDurationSec = ((headerEndTimeMs - headerStartTimeMs) / 1000).toInt()
                    headerDistanceKm = 0.0
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                // Persistent ride stats header
                RideStatsHeader(
                    startTimeMs = headerStartTimeMs,
                    endTimeMs = headerEndTimeMs,
                    durationSeconds = headerDurationSec,
                    distanceKm = headerDistanceKm,
                    maxSpeedKmh = headerMaxSpeedKmh,
                    maxPwmPercent = headerMaxPwmPercent,
                    useMph = useMph
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Replay live telemetry panel (only during replay)
                    if (rc != null) {
                        val sample = rc.currentSample
                        if (sample != null) {
                            ReplayStatsPanel(
                                sample = sample,
                                useMph = useMph,
                                useFahrenheit = useFahrenheit,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // Toggle chips
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { SeriesChip(MetricType.SPEED.label, SPEED_COLOR, showSpeed) { showSpeed = !showSpeed } }
                        item { SeriesChip(ChartLabels.GPS, GPS_SPEED_COLOR, showGpsSpeed) { showGpsSpeed = !showGpsSpeed } }
                        item { SeriesChip(ChartLabels.CURRENT, CURRENT_COLOR, showCurrent) { showCurrent = !showCurrent } }
                        item { SeriesChip(MetricType.POWER.label, POWER_COLOR, showPower) { showPower = !showPower } }
                        item { SeriesChip(MetricType.TEMPERATURE.label, TEMP_COLOR, showTemperature) { showTemperature = !showTemperature } }
                        item { SeriesChip(MetricType.PWM.label, PWM_COLOR, showPwm) { showPwm = !showPwm } }
                        item { SeriesChip(ChartLabels.VOLTAGE, VOLTAGE_COLOR, showVoltage) { showVoltage = !showVoltage } }
                    }

                    // Route map (only when GPS data is present)
                    if (s.routePoints.size >= 2) {
                        RideMapView(
                            routePoints = s.routePoints,
                            selectedPoint = mapSelectedPoint,
                            onTapPoint = { point: RoutePoint? ->
                                mapSelectedPoint = point
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
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
                    if (showVoltage) {
                        visibleSeries += SeriesInfo(VOLTAGE_COLOR, s.samples.map { it.voltageV })
                        visibleMarkerInfo += MarkerSeriesInfo(ChartLabels.VOLTAGE, "V", 1)
                    }

                    val timeFormatPattern = "HH:mm"

                    if (visibleSeries.isNotEmpty()) {
                        val marker = rememberChartMarker(s.samples, visibleMarkerInfo, timeFormatPattern)
                        val yAxisUnits = buildList {
                            if (showSpeed || showGpsSpeed) add(speedUnit)
                            if (showCurrent) add("A")
                            if (showPower) add("W")
                            if (showTemperature) add(tempUnit)
                            if (showPwm) add("%")
                            if (showVoltage) add("V")
                        }
                        VicoLineChart(
                            samples = s.samples,
                            seriesList = visibleSeries,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .padding(horizontal = 16.dp),
                            marker = marker,
                            yAxisUnit = yAxisUnits.joinToString(" · ").ifEmpty { null },
                            onSelectedIndexChanged = { index ->
                                if (index != null && s.routePoints.isNotEmpty()) {
                                    val sample = s.samples.getOrNull(index)
                                    if (sample != null) {
                                        mapSelectedPoint = ChartDataPrep.nearestRoutePoint(
                                            s.routePoints, sample.timestampMs
                                        )
                                    }
                                } else {
                                    mapSelectedPoint = null
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // Replay controls (fixed at bottom)
                if (rc != null) {
                    RideReplayControls(controller = rc)
                }
                }
            }
        }
    }
}

@Composable
private fun SeriesChip(
    label: String,
    color: Color,
    selected: Boolean,
    onToggle: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.2f),
            selectedLabelColor = color
        )
    )
}
