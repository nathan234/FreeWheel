package com.cooper.wheellog.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cooper.wheellog.core.telemetry.ChartTimeRange

/**
 * Horizontal row of filter chips for selecting a chart time range.
 * Used in both ChartScreen and MetricDetailScreen.
 */
@Composable
fun TimeRangePicker(
    selected: ChartTimeRange,
    onSelect: (ChartTimeRange) -> Unit
) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (range in ChartTimeRange.entries) {
            item {
                FilterChip(
                    selected = selected == range,
                    onClick = { onSelect(range) },
                    label = { Text(range.label) }
                )
            }
        }
    }
}
