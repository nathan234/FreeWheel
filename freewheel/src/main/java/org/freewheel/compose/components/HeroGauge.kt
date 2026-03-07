package org.freewheel.compose.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.core.domain.dashboard.ColorZone
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.utils.StringUtil
import java.util.Locale
import kotlin.math.abs

/**
 * Generic arc gauge for non-speed hero metrics (PWM, Battery, Power, etc.).
 * Simpler than [SpeedGauge] — no GPS mode, no speed-specific tick marks.
 */
@Composable
fun HeroGauge(
    value: Double,
    maxValue: Double,
    unitLabel: String,
    label: String,
    metric: DashboardMetric,
    modifier: Modifier = Modifier
) {
    val effectiveMax = if (maxValue > 0) maxValue else abs(value).coerceAtLeast(1.0)
    val progress = (abs(value) / effectiveMax).toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "hero_progress"
    )

    val arcColor = when (metric.colorZone(progress.toDouble())) {
        ColorZone.GREEN -> Color(0xFF4CAF50)
        ColorZone.ORANGE -> Color(0xFFFF9800)
        ColorZone.RED -> Color(0xFFF44336)
    }

    val formattedValue = String.format(Locale.US, "%.${metric.decimals}f", value)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 16.dp.toPx()
            val arcSize = Size(
                width = size.width - strokeWidth,
                height = (size.height - strokeWidth) * 2
            )
            val topLeft = androidx.compose.ui.geometry.Offset(
                x = strokeWidth / 2,
                y = strokeWidth / 2
            )

            // Track arc (180° from left to right)
            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Progress arc
            drawArc(
                color = arcColor,
                startAngle = 180f,
                sweepAngle = 180f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formattedValue,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = unitLabel,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
