package org.freewheel.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.domain.dashboard.ColorZone
import org.freewheel.ui.theme.ZoneColors
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
 * - Extracting the value from TelemetryState (with unit conversion)
 * - Computing progress and color from the metric's thresholds
 * - Providing sparkline data via sparklineKey mapping
 *
 * [effectiveMaxOverrideKmh] lets callers inject a wheel-aware max (e.g.
 * [org.freewheel.core.domain.identity.wheel.WheelCatalog.resolveTopSpeedKmh] for SPEED).
 * When null, the per-metric [DashboardMetric.effectiveMax] fallback is used.
 */
@Composable
fun RenderGaugeTile(
    metric: DashboardMetric,
    telemetry: TelemetryState,
    gpsSpeed: Double,
    telemetryBuffer: TelemetryBuffer,
    samples: List<TelemetrySample>,
    useMph: Boolean,
    useFahrenheit: Boolean,
    onNavigateToMetric: (String) -> Unit,
    onLongPress: (() -> Unit)? = null,
    effectiveMaxOverrideKmh: Double? = null,
    modifier: Modifier = Modifier
) {
    val rawValue = metric.extractValue(telemetry) ?: gpsSpeed

    val displayValue = DisplayUtils.convertDashboardMetricValue(rawValue, metric, useMph, useFahrenheit)
    val displayUnit = DisplayUtils.dashboardMetricUnit(metric, useMph, useFahrenheit)
    val effectiveMax = effectiveMaxOverrideKmh?.takeIf { it > 0.0 } ?: metric.effectiveMax(telemetry)
    val maxValue = maxForDisplay(metric, useMph, effectiveMax)

    val metricType = metric.sparklineKey
    val sparkline = remember(samples) {
        metricType?.let { telemetryBuffer.valuesFor(it).takeLast(20).map { v -> v.toFloat() } }
            ?: emptyList()
    }

    val progress = if (maxValue > 0) (abs(displayValue) / maxValue).toFloat() else 0f
    val rawProgress = if (effectiveMax > 0) (abs(rawValue) / effectiveMax) else 0.0
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
        onLongPress = onLongPress,
        modifier = modifier
    )
}

/**
 * Renders a [DashboardMetric] as a [StatRow] composable.
 * Applies color coding based on wheel-aware thresholds for metrics with meaningful danger levels.
 */
@Composable
fun RenderStatRow(
    metric: DashboardMetric,
    telemetry: TelemetryState,
    gpsSpeed: Double,
    useMph: Boolean,
    useFahrenheit: Boolean,
    effectiveMaxOverrideKmh: Double? = null,
    modifier: Modifier = Modifier
) {
    val rawValue = metric.extractValue(telemetry) ?: gpsSpeed

    val displayValue = DisplayUtils.convertDashboardMetricValue(rawValue, metric, useMph, useFahrenheit)
    val displayUnit = DisplayUtils.dashboardMetricUnit(metric, useMph, useFahrenheit)

    val formatted = when {
        metric == DashboardMetric.FAN_STATUS -> if (rawValue > 0) "On" else "Off"
        metric.isDistanceMetric -> DisplayUtils.formatDistance(rawValue, useMph, decimals = metric.decimals)
        else -> "${StringUtil.formatDecimal(displayValue, metric.decimals)} $displayUnit"
    }

    val effectiveMax = effectiveMaxOverrideKmh?.takeIf { it > 0.0 } ?: metric.effectiveMax(telemetry)
    val rawProgress = if (effectiveMax > 0) (abs(rawValue) / effectiveMax) else 0.0
    val zone = metric.colorZone(rawProgress)
    val valueColor = if (zone != ColorZone.GREEN) tileColorForMetric(metric, rawProgress) else Color.Unspecified

    StatRow(
        label = metric.label,
        value = formatted,
        valueColor = valueColor,
        modifier = modifier
    )
}

/**
 * Display-unit max used for the gauge fill ratio.
 * For speed metrics, converts the resolved [effectiveMaxKmh] to mph if needed.
 * For other metrics, uses the metric's static [DashboardMetric.maxValue].
 */
private fun maxForDisplay(metric: DashboardMetric, useMph: Boolean, effectiveMaxKmh: Double): Double = when {
    metric.isSpeedMetric -> if (useMph) {
        effectiveMaxKmh * org.freewheel.core.utils.ByteUtils.KM_TO_MILES_MULTIPLIER
    } else {
        effectiveMaxKmh
    }
    else -> metric.maxValue
}

private fun tileColorForMetric(metric: DashboardMetric, progress: Double): Color {
    return ZoneColors.forZone(metric.colorZone(progress))
}
