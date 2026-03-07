package org.freewheel.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.dashboard.ColorZone
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.core.utils.StringUtil
import java.util.Locale
import kotlin.math.abs

/**
 * Renders a [DashboardMetric] as a [GaugeTile] composable.
 *
 * Bridges the KMP metric model to the existing GaugeTile component by:
 * - Extracting the value from WheelState (with unit conversion)
 * - Computing progress and color from the metric's thresholds
 * - Providing sparkline data via sparklineKey mapping
 */
@Composable
fun RenderGaugeTile(
    metric: DashboardMetric,
    wheelState: WheelState,
    gpsSpeed: Double,
    telemetryBuffer: TelemetryBuffer,
    samples: List<TelemetrySample>,
    useMph: Boolean,
    useFahrenheit: Boolean,
    onNavigateToMetric: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rawValue = metric.extractValue(wheelState) ?: gpsSpeed

    val displayValue = DisplayUtils.convertMetricValue(rawValue, metric, useMph, useFahrenheit)
    val displayUnit = DisplayUtils.metricUnit(metric, useMph, useFahrenheit)
    val maxValue = maxForDisplay(metric, useMph)

    val metricType = metric.sparklineKey
    val sparkline = remember(samples) {
        metricType?.let { telemetryBuffer.valuesFor(it).takeLast(20).map { v -> v.toFloat() } }
            ?: emptyList()
    }

    val progress = if (maxValue > 0) (abs(displayValue) / maxValue).toFloat() else 0f
    val rawProgress = if (metric.maxValue > 0) (abs(rawValue) / metric.maxValue) else 0.0
    val color = tileColorForMetric(metric, rawProgress)

    val formattedValue = if (metric == DashboardMetric.GPS_SPEED && gpsSpeed <= 0) {
        "\u2014"
    } else {
        String.format(Locale.US, "%.${metric.decimals}f", displayValue)
    }

    GaugeTile(
        label = metric.label,
        value = formattedValue,
        unit = displayUnit,
        progress = progress,
        color = color,
        sparklineData = sparkline,
        onClick = {
            val metricId = metricType?.name?.lowercase() ?: metric.name.lowercase()
            onNavigateToMetric(metricId)
        },
        modifier = modifier
    )
}

/**
 * Renders a [DashboardMetric] as a [StatRow] composable.
 */
@Composable
fun RenderStatRow(
    metric: DashboardMetric,
    wheelState: WheelState,
    gpsSpeed: Double,
    useMph: Boolean,
    useFahrenheit: Boolean,
    modifier: Modifier = Modifier
) {
    val rawValue = metric.extractValue(wheelState) ?: gpsSpeed

    val displayValue = DisplayUtils.convertMetricValue(rawValue, metric, useMph, useFahrenheit)
    val displayUnit = DisplayUtils.metricUnit(metric, useMph, useFahrenheit)

    val formatted = when {
        metric == DashboardMetric.FAN_STATUS -> if (rawValue > 0) "On" else "Off"
        metric.isDistanceMetric -> DisplayUtils.formatDistance(rawValue, useMph, decimals = metric.decimals)
        else -> "${StringUtil.formatDecimal(displayValue, metric.decimals)} $displayUnit"
    }

    StatRow(
        label = metric.label,
        value = formatted,
        modifier = modifier
    )
}

private fun maxForDisplay(metric: DashboardMetric, useMph: Boolean): Double = when {
    metric.isSpeedMetric && metric.maxValue > 0 -> DisplayUtils.maxSpeedDefault(useMph)
    else -> metric.maxValue
}

private fun tileColorForMetric(metric: DashboardMetric, progress: Double): Color {
    return when (metric.colorZone(progress)) {
        ColorZone.GREEN -> Color(0xFF4CAF50)
        ColorZone.ORANGE -> Color(0xFFFF9800)
        ColorZone.RED -> Color(0xFFF44336)
    }
}
