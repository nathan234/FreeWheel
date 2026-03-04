package org.freewheel.compose.screens

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
//  1. Title header
//  2. Empty state with icon and message
//  3. Ride list with swipe-to-delete
//  4. Ride row: friendly date, duration | distance, max speed | avg speed
//  5. Ride row (optional): power | energy stats
//  6. Share button per ride
//  Note: Android navigates to TripDetailScreen on tap; iOS uses NavigationLink

@Composable
fun RidesScreen(
    viewModel: WheelViewModel,
    onNavigateToTripDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var trips by remember { mutableStateOf<List<TripDataDbEntry>>(emptyList()) }
    val useMph = viewModel.appConfig.useMph

    LaunchedEffect(Unit) {
        trips = viewModel.loadTrips()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 16.dp)
    ) {
        Text(
            RidesLabels.TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

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
                            onShare = {
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
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RideRow(
    trip: TripDataDbEntry,
    useMph: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        IconButton(onClick = onShare) {
            Icon(
                Icons.Default.Share,
                contentDescription = CommonLabels.SHARE,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
