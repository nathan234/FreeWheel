package org.freewheel.compose.screens

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.domain.RidesLabels
import org.freewheel.data.TripDataDbEntry
import org.freewheel.core.utils.DisplayUtils
import org.freewheel.core.utils.PlatformDateFormatter
import java.io.File

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/RidesView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Title header (or selection count in select mode)
//  2. Empty state with icon and message
//  3. Ride list with swipe-to-delete (disabled in select mode)
//  4. Ride row: friendly date, duration | distance, max speed | avg speed
//  5. Ride row (optional): power | energy stats
//  6. Share button per ride (checkbox in select mode)
//  7. Bottom bar with "Merge Rides" button (select mode, 2+ selected)
//  Note: Android navigates to TripDetailScreen on tap; iOS uses NavigationLink
//  Note: Long-press a ride to enter select mode for merging

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(
    viewModel: WheelViewModel,
    onNavigateToTripDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trips by remember { mutableStateOf<List<TripDataDbEntry>>(emptyList()) }
    val useMph = viewModel.appConfig.useMph

    var isSelecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Int>()) }
    var showMergeConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        trips = viewModel.loadTrips()
    }

    // Merge confirmation dialog
    if (showMergeConfirm) {
        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            title = { Text(RidesLabels.MERGE_CONFIRM_TITLE) },
            text = { Text(RidesLabels.MERGE_CONFIRM_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    showMergeConfirm = false
                    val selected = trips.filter { it.id in selectedIds }
                    scope.launch {
                        if (viewModel.stitchRides(selected, context)) {
                            isSelecting = false
                            selectedIds = emptySet()
                            trips = viewModel.loadTrips()
                        }
                    }
                }) {
                    Text(RidesLabels.MERGE_RIDES, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirm = false }) {
                    Text(CommonLabels.CANCEL)
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            if (isSelecting && selectedIds.size >= 2) {
                BottomAppBar {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showMergeConfirm = true },
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(RidesLabels.MERGE_RIDES)
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            // Title row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isSelecting) "${selectedIds.size} ${RidesLabels.SELECTED_SUFFIX}" else RidesLabels.TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isSelecting) {
                    IconButton(onClick = {
                        isSelecting = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = CommonLabels.CANCEL)
                    }
                }
            }

            if (trips.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Route,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        RidesLabels.EMPTY_TITLE,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        RidesLabels.EMPTY_SUBTITLE,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(trips, key = { it.id }) { trip ->
                        if (isSelecting) {
                            // Selection mode: checkbox row, no swipe
                            RideRow(
                                trip = trip,
                                useMph = useMph,
                                onClick = {
                                    selectedIds = if (trip.id in selectedIds) {
                                        selectedIds - trip.id
                                    } else {
                                        selectedIds + trip.id
                                    }
                                },
                                trailing = {
                                    Checkbox(
                                        checked = trip.id in selectedIds,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + trip.id else selectedIds - trip.id
                                        }
                                    )
                                }
                            )
                        } else {
                            // Normal mode: swipe-to-delete + long-press to select
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        trips = trips.filter { it.id != trip.id }
                                        viewModel.deleteTrip(trip, context)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                            MaterialTheme.colorScheme.errorContainer
                                        else Color.Transparent,
                                        label = "swipe-bg"
                                    )
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(color)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = CommonLabels.DELETE,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false
                            ) {
                                RideRow(
                                    trip = trip,
                                    useMph = useMph,
                                    onClick = { onNavigateToTripDetail(trip.fileName) },
                                    onLongClick = {
                                        isSelecting = true
                                        selectedIds = setOf(trip.id)
                                    },
                                    trailing = {
                                        IconButton(onClick = {
                                            val ridesDir = File(context.getExternalFilesDir(null), "rides")
                                            val csvFile = File(ridesDir, trip.fileName)
                                            if (csvFile.exists()) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    csvFile
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/csv"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, RidesLabels.SHARE_RIDE))
                                            }
                                        }) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = CommonLabels.SHARE,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RideRow(
    trip: TripDataDbEntry,
    useMph: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Title: friendly date
            Text(
                text = PlatformDateFormatter.formatFriendlyDate(trip.start.toLong() * 1000),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            // Line 1: Duration + Distance
            val durationStr = DisplayUtils.formatDurationShort(trip.duration)
            val distStr = DisplayUtils.formatDistance(trip.distance / 1000.0, useMph)
            Text(
                text = "$durationStr  |  $distStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Line 2: Max speed + Avg speed
            val maxSpeedStr = DisplayUtils.formatSpeed(trip.maxSpeed.toDouble(), useMph)
            val avgSpeedStr = DisplayUtils.formatSpeed(trip.avgSpeed.toDouble(), useMph)
            Text(
                text = "$maxSpeedStr max  |  $avgSpeedStr avg",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Line 3: Power + Energy (if data exists)
            if (trip.maxPower > 0 || trip.consumptionByKm > 0) {
                val parts = mutableListOf<String>()
                if (trip.maxPower > 0) {
                    parts.add("${trip.maxPower.toInt()} W max")
                }
                if (trip.consumptionByKm > 0) {
                    parts.add(DisplayUtils.formatEnergyConsumption(trip.consumptionByKm.toDouble(), useMph))
                }
                Text(
                    text = parts.joinToString("  |  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}
