package org.freewheel.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.freewheel.core.telemetry.MetricType
import org.freewheel.core.telemetry.TelemetrySample
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val SPEED_COLOR = Color(MetricType.SPEED.colorHex)
val GPS_SPEED_COLOR = Color(MetricType.GPS_SPEED.colorHex)
val CURRENT_COLOR = Color(MetricType.CURRENT_COLOR_HEX)
val POWER_COLOR = Color(MetricType.POWER.colorHex)
val TEMP_COLOR = Color(MetricType.TEMPERATURE.colorHex)
val PWM_COLOR = Color(MetricType.PWM.colorHex)
val VOLTAGE_COLOR = Color(MetricType.VOLTAGE_COLOR_HEX)

data class SeriesInfo(
    val color: Color,
    val values: List<Double>
)

fun metricColor(metric: MetricType): Color = when (metric) {
    MetricType.SPEED -> SPEED_COLOR
    MetricType.BATTERY -> POWER_COLOR
    MetricType.POWER -> CURRENT_COLOR
    MetricType.PWM -> VOLTAGE_COLOR
    MetricType.TEMPERATURE -> TEMP_COLOR
    MetricType.GPS_SPEED -> GPS_SPEED_COLOR
}

@Composable
fun VicoLineChart(
    samples: List<TelemetrySample>,
    seriesList: List<SeriesInfo>,
    modifier: Modifier = Modifier,
    timeFormatPattern: String = "HH:mm",
    marker: CartesianMarker? = null,
    zoomEnabled: Boolean = true,
    yAxisUnit: String? = null,
    onSelectedIndexChanged: ((Int?) -> Unit)? = null,
) {
    if (samples.isEmpty() || seriesList.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(samples, seriesList.map { it.values.hashCode() }) {
        modelProducer.runTransaction {
            lineSeries {
                for (info in seriesList) {
                    series(y = info.values)
                }
            }
        }
    }

    val timeFormat = remember(timeFormatPattern) { SimpleDateFormat(timeFormatPattern, Locale.US) }
    val firstTimestamp = samples.firstOrNull()?.timestampMs ?: 0L
    val bottomAxisFormatter = remember(firstTimestamp, samples.size, timeFormatPattern) {
        CartesianValueFormatter { _, value, _ ->
            val index = value.toInt().coerceIn(0, samples.lastIndex)
            timeFormat.format(Date(samples[index].timestampMs))
        }
    }

    val markerVisibilityListener = remember(onSelectedIndexChanged) {
        if (onSelectedIndexChanged == null) return@remember null
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                val index = targets.firstOrNull()?.x?.toInt()
                onSelectedIndexChanged(index)
            }
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                val index = targets.firstOrNull()?.x?.toInt()
                onSelectedIndexChanged(index)
            }
            override fun onHidden(marker: CartesianMarker) {
                onSelectedIndexChanged(null)
            }
        }
    }

    var resetKey by remember { mutableIntStateOf(0) }
    var isZoomed by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        key(resetKey) {
            val scrollState = rememberVicoScrollState(scrollEnabled = zoomEnabled)
            val zoomState = rememberVicoZoomState(
                zoomEnabled = zoomEnabled,
                initialZoom = Zoom.Content,
                minZoom = Zoom.Content,
            )

            LaunchedEffect(scrollState.maxValue > 0f) {
                isZoomed = scrollState.maxValue > 0f
            }

            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        LineCartesianLayer.LineProvider.series(
                            seriesList.map { info ->
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(fill(info.color)),
                                    areaFill = null,
                                )
                            }
                        )
                    ),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisFormatter),
                    marker = marker,
                    markerVisibilityListener = markerVisibilityListener,
                ),
                modelProducer = modelProducer,
                scrollState = scrollState,
                zoomState = zoomState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!yAxisUnit.isNullOrEmpty()) {
            Text(
                yAxisUnit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }

        if (isZoomed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clickable { resetKey++; isZoomed = false },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                tonalElevation = 2.dp,
            ) {
                Text(
                    "Fit All",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
