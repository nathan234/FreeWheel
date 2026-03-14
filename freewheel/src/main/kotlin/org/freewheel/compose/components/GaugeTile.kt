package org.freewheel.compose.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GaugeTile(
    label: String,
    value: String,
    unit: String,
    progress: Float,
    color: Color,
    sparklineData: List<Float>,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "gauge_progress"
    )

    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Surface(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .semantics { contentDescription = "$label: $value $unit" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(10.dp)
        ) {
            // Label at top-left
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart)
            )

            // Gauge arc + center value
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasSize = min(size.width, size.height)
                val lineWidth = canvasSize * 0.06f
                val arcRadius = canvasSize * 0.38f
                val centerX = size.width / 2f
                val centerY = size.height * 0.48f

                // Semi-circular arc: 180° sweep from left to right
                val arcTopLeft = Offset(centerX - arcRadius, centerY - arcRadius)
                val arcSize = Size(arcRadius * 2, arcRadius * 2)

                // Background arc
                drawArc(
                    color = trackColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    style = Stroke(width = lineWidth, cap = StrokeCap.Round),
                    topLeft = arcTopLeft,
                    size = arcSize
                )

                // Progress arc
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = lineWidth, cap = StrokeCap.Round),
                    topLeft = arcTopLeft,
                    size = arcSize
                )

                // Sparkline in bottom-right corner
                if (sparklineData.size >= 2) {
                    val sparkWidth = size.width * 0.4f
                    val sparkHeight = size.height * 0.18f
                    val sparkLeft = size.width - sparkWidth - 4f
                    val sparkTop = size.height - sparkHeight - 4f

                    val minVal = sparklineData.min()
                    val maxVal = sparklineData.max()
                    val range = (maxVal - minVal).coerceAtLeast(0.01f)

                    val path = Path()
                    sparklineData.forEachIndexed { index, value ->
                        val x = sparkLeft + (index.toFloat() / (sparklineData.size - 1)) * sparkWidth
                        val y = sparkTop + sparkHeight - ((value - minVal) / range) * sparkHeight
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.4f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round)
                    )
                }
            }

            // Center value + unit
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = color
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
