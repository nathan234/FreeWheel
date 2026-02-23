package com.cooper.wheellog.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cooper.wheellog.compose.WheelViewModel
import com.cooper.wheellog.core.domain.CommonLabels
import com.cooper.wheellog.compose.components.MarkerSeriesInfo
import com.cooper.wheellog.compose.components.metricColor
import com.cooper.wheellog.compose.components.rememberChartMarker
import com.cooper.wheellog.compose.components.TimeRangePicker
import com.cooper.wheellog.core.telemetry.ChartTimeRange
import com.cooper.wheellog.core.telemetry.MetricType
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.cooper.wheellog.core.utils.DisplayUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    viewModel: WheelViewModel,
    metricId: String,
    onBack: () -> Unit
) {
    val selectedRange by viewModel.chartTimeRange.collectAsState()
    val samples by viewModel.chartSamples.collectAsState()
    val useMph = viewModel.appConfig.useMph
    val useFahrenheit = viewModel.appConfig.useFahrenheit

    val metric = remember(metricId) {
        MetricType.entries.firstOrNull { it.name.lowercase() == metricId.lowercase() }
            ?: MetricType.SPEED
    }

    val displayUnit = DisplayUtils.metricUnit(metric, useMph, useFahrenheit)

    val values = samples.map { sample ->
        val raw = metric.extractValue(sample)
        DisplayUtils.convertMetricValue(raw, metric, useMph, useFahrenheit)
    }

    val currentValue = values.lastOrNull() ?: 0.0
    val stats = viewModel.telemetryBuffer.statsFor(metric)

    val chartColor = metricColor(metric)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metric.label) },
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

            // Current value
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = metric.formatValue(currentValue),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = chartColor
                )
                Text(
                    text = displayUnit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chart
            if (values.isNotEmpty()) {
                val modelProducer = remember { CartesianChartModelProducer() }

                LaunchedEffect(values) {
                    modelProducer.runTransaction {
                        lineSeries { series(y = values) }
                    }
                }

                val timeFormatPattern = if (selectedRange == ChartTimeRange.FIVE_MINUTES) "mm:ss" else "HH:mm"
                val timeFormat = remember(timeFormatPattern) { SimpleDateFormat(timeFormatPattern, Locale.US) }
                val bottomAxisFormatter = remember(samples.size, timeFormatPattern) {
                    CartesianValueFormatter { _, value, _ ->
                        val index = value.toInt().coerceIn(0, samples.lastIndex)
                        timeFormat.format(Date(samples[index].timestampMs))
                    }
                }

                val marker = rememberChartMarker(
                    samples,
                    listOf(MarkerSeriesInfo(metric.label, displayUnit, metric.decimals)),
                    timeFormatPattern,
                )

                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(fill(chartColor)),
                                    areaFill = null,
                                )
                            )
                        ),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisFormatter),
                        marker = marker,
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Stats
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(CommonLabels.MIN, metric.formatValue(DisplayUtils.convertMetricValue(stats.min, metric, useMph, useFahrenheit)), displayUnit)
                    StatItem(CommonLabels.AVG, metric.formatValue(DisplayUtils.convertMetricValue(stats.avg, metric, useMph, useFahrenheit)), displayUnit)
                    StatItem(CommonLabels.MAX, metric.formatValue(DisplayUtils.convertMetricValue(stats.max, metric, useMph, useFahrenheit)), displayUnit)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

