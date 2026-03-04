package org.freewheel.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.core.telemetry.TelemetrySample
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.common.shape.MarkerCorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MarkerSeriesInfo(
    val label: String,
    val unit: String,
    val decimals: Int = 1,
)

@Composable
fun rememberChartMarker(
    samples: List<TelemetrySample>,
    seriesInfoList: List<MarkerSeriesInfo>,
    timeFormatPattern: String,
): CartesianMarker {
    val timeFormat = remember(timeFormatPattern) { SimpleDateFormat(timeFormatPattern, Locale.US) }

    val valueFormatter = remember(samples, seriesInfoList, timeFormatPattern) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val target = targets.firstOrNull() ?: return@ValueFormatter ""
            val index = target.x.toInt().coerceIn(0, samples.lastIndex)
            val timestamp = timeFormat.format(Date(samples[index].timestampMs))

            buildString {
                if (target is LineCartesianLayerMarkerTarget) {
                    for ((i, point) in target.points.withIndex()) {
                        if (i < seriesInfoList.size) {
                            val info = seriesInfoList[i]
                            val formatted = String.format(Locale.US, "%.${info.decimals}f", point.entry.y)
                            append("${info.label}: $formatted ${info.unit}\n")
                        }
                    }
                }
                append(timestamp)
            }
        }
    }

    val labelColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val outlineColor = MaterialTheme.colorScheme.outline

    val label = rememberTextComponent(
        color = labelColor,
        textSize = 12.sp,
        lineCount = seriesInfoList.size + 1,
        padding = Insets(8f, 4f, 8f, 4f),
        background = rememberShapeComponent(
            fill = Fill(surfaceColor.toArgb()),
            shape = MarkerCorneredShape(CorneredShape.rounded(allDp = 8f)),
            strokeFill = Fill(outlineColor.toArgb()),
            strokeThickness = 1f.dp,
        ),
    )

    val guideline = rememberLineComponent(
        fill = Fill(outlineColor.copy(alpha = 0.5f).toArgb()),
        thickness = 1f.dp,
    )

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        labelPosition = DefaultCartesianMarker.LabelPosition.AbovePoint,
        indicator = { color ->
            ShapeComponent(fill = Fill(color.toArgb()), shape = CorneredShape.Pill)
        },
        indicatorSize = 8.dp,
        guideline = guideline,
    )
}
