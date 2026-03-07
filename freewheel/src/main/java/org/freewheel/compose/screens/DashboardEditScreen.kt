package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.WheelType
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.domain.dashboard.DashboardPresets
import org.freewheel.core.domain.dashboard.DashboardSection
import org.freewheel.core.domain.dashboard.WidgetType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardEditScreen(
    viewModel: WheelViewModel,
    onBack: () -> Unit
) {
    val currentLayout by viewModel.dashboardLayout.collectAsStateWithLifecycle()
    val wheelState by viewModel.wheelState.collectAsStateWithLifecycle()
    val wheelType = wheelState.wheelType

    // Local mutable state
    var heroMetric by remember(currentLayout) { mutableStateOf(currentLayout.heroMetric) }
    val tiles = remember(currentLayout) { mutableStateListOf(*currentLayout.tiles.toTypedArray()) }
    val stats = remember(currentLayout) { mutableStateListOf(*currentLayout.stats.toTypedArray()) }
    var showWheelSettings by remember(currentLayout) { mutableStateOf(currentLayout.showWheelSettings) }
    var showWheelInfo by remember(currentLayout) { mutableStateOf(currentLayout.showWheelInfo) }

    val isLayoutValid = remember(heroMetric, tiles.toList(), stats.toList()) {
        WidgetType.HERO_GAUGE in heroMetric.supportedDisplayTypes &&
        tiles.all { WidgetType.GAUGE_TILE in it.supportedDisplayTypes } &&
        stats.all { WidgetType.STAT_ROW in it.supportedDisplayTypes }
    }

    var showAddTileDialog by remember { mutableStateOf(false) }
    var showAddStatDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edit Dashboard") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val sections = mutableSetOf<DashboardSection>()
                            if (showWheelSettings) sections += DashboardSection.WHEEL_SETTINGS
                            if (showWheelInfo) sections += DashboardSection.WHEEL_INFO
                            val layout = DashboardLayout.create(
                                heroMetric = heroMetric,
                                tiles = tiles.toList(),
                                stats = stats.toList(),
                                sections = sections
                            )
                            viewModel.saveDashboardLayout(layout)
                            onBack()
                        },
                        enabled = isLayoutValid
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preset picker
            SectionTitle("Presets")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DashboardPresets.all()) { preset ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            val presetLayout = preset.layout.filteredFor(wheelType)
                            heroMetric = presetLayout.heroMetric
                            tiles.clear()
                            tiles.addAll(presetLayout.tiles)
                            stats.clear()
                            stats.addAll(presetLayout.stats)
                            showWheelSettings = presetLayout.showWheelSettings
                            showWheelInfo = presetLayout.showWheelInfo
                        },
                        label = { Text(preset.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }

            // Hero metric
            SectionTitle("Hero Gauge")
            val heroOptions = DashboardMetric.entries.filter {
                WidgetType.HERO_GAUGE in it.supportedDisplayTypes && it.isAvailableFor(wheelType)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    heroOptions.forEach { metric ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = heroMetric == metric,
                                onClick = { heroMetric = metric }
                            )
                            Text(metric.label)
                        }
                    }
                }
            }

            // Tiles
            SectionTitle("Gauge Tiles")
            MetricListEditor(
                metrics = tiles,
                widgetType = WidgetType.GAUGE_TILE,
                wheelType = wheelType,
                allSelected = tiles + stats + listOf(heroMetric),
                onAdd = { showAddTileDialog = true },
                onRemove = { tiles.remove(it) },
                onMoveUp = { idx -> if (idx > 0) { val item = tiles.removeAt(idx); tiles.add(idx - 1, item) } },
                onMoveDown = { idx -> if (idx < tiles.size - 1) { val item = tiles.removeAt(idx); tiles.add(idx + 1, item) } }
            )

            // Stats
            SectionTitle("Stat Rows")
            MetricListEditor(
                metrics = stats,
                widgetType = WidgetType.STAT_ROW,
                wheelType = wheelType,
                allSelected = tiles + stats + listOf(heroMetric),
                onAdd = { showAddStatDialog = true },
                onRemove = { stats.remove(it) },
                onMoveUp = { idx -> if (idx > 0) { val item = stats.removeAt(idx); stats.add(idx - 1, item) } },
                onMoveDown = { idx -> if (idx < stats.size - 1) { val item = stats.removeAt(idx); stats.add(idx + 1, item) } }
            )

            // Info card toggles
            SectionTitle("Info Cards")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Wheel Settings")
                        Switch(checked = showWheelSettings, onCheckedChange = { showWheelSettings = it })
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Wheel Info")
                        Switch(checked = showWheelInfo, onCheckedChange = { showWheelInfo = it })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Add tile dialog
        if (showAddTileDialog) {
            AddMetricDialog(
                title = "Add Gauge Tile",
                widgetType = WidgetType.GAUGE_TILE,
                wheelType = wheelType,
                alreadySelected = (tiles + stats).toSet(),
                onSelect = { tiles.add(it); showAddTileDialog = false },
                onDismiss = { showAddTileDialog = false }
            )
        }

        // Add stat dialog
        if (showAddStatDialog) {
            AddMetricDialog(
                title = "Add Stat Row",
                widgetType = WidgetType.STAT_ROW,
                wheelType = wheelType,
                alreadySelected = (tiles + stats).toSet(),
                onSelect = { stats.add(it); showAddStatDialog = false },
                onDismiss = { showAddStatDialog = false }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun MetricListEditor(
    metrics: List<DashboardMetric>,
    widgetType: WidgetType,
    wheelType: WheelType,
    allSelected: List<DashboardMetric>,
    onAdd: () -> Unit,
    onRemove: (DashboardMetric) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            metrics.forEachIndexed { index, metric ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        metric.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(onClick = { onMoveUp(index) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMoveDown(index) }, enabled = index < metrics.size - 1) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                    }
                    IconButton(onClick = { onRemove(metric) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
                if (index < metrics.size - 1) {
                    HorizontalDivider()
                }
            }

            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add ${if (widgetType == WidgetType.GAUGE_TILE) "Tile" else "Stat"}")
            }
        }
    }
}

@Composable
private fun AddMetricDialog(
    title: String,
    widgetType: WidgetType,
    wheelType: WheelType,
    alreadySelected: Set<DashboardMetric>,
    onSelect: (DashboardMetric) -> Unit,
    onDismiss: () -> Unit
) {
    val available = DashboardMetric.entries.filter { metric ->
        widgetType in metric.supportedDisplayTypes &&
            metric.isAvailableFor(wheelType) &&
            metric !in alreadySelected
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (available.isEmpty()) {
                    Text("No more metrics available")
                } else {
                    available.forEach { metric ->
                        TextButton(
                            onClick = { onSelect(metric) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${metric.label} (${metric.unit})",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
