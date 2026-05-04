package org.freewheel.compose.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.components.LiveRideMapView
import org.freewheel.core.domain.AppSettingId
import org.freewheel.core.location.ChargingStation
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.DisplayUtils

@Composable
fun MapScreen(viewModel: WheelViewModel) {
    // Keep GPS flowing while the Map tab is visible, so the user-dot and
    // camera follow work even when no wheel is connected.
    DisposableEffect(Unit) {
        viewModel.requestLocationTracking()
        onDispose { viewModel.releaseLocationTrackingIfIdle() }
    }

    val isLogging by viewModel.isLogging.collectAsStateWithLifecycle()
    val routePoints by viewModel.liveRoutePoints.collectAsStateWithLifecycle()
    val speedRange by viewModel.liveRouteSpeedRange.collectAsStateWithLifecycle()
    val location by viewModel.lastGpsLocation.collectAsStateWithLifecycle()
    val chargers by viewModel.nearbyChargers.collectAsStateWithLifecycle()
    val currentLatLng = location?.let { LatLng(it.latitude, it.longitude) }

    var followMode by rememberSaveable { mutableStateOf(true) }
    var selectedCharger by remember { mutableStateOf<ChargingStation?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LiveRideMapView(
            routePoints = routePoints,
            speedRange = speedRange,
            currentLatLng = currentLatLng,
            followMode = followMode,
            onUserPanned = { followMode = false },
            modifier = Modifier.fillMaxSize(),
            chargers = chargers,
            onChargerTap = { selectedCharger = it },
            onCameraIdle = { target ->
                viewModel.refreshChargers(target.latitude, target.longitude)
            }
        )

        if (isLogging) {
            LiveRideOverlayCard(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = !followMode,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp)
        ) {
            FloatingActionButton(onClick = { followMode = true }) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recenter")
            }
        }
    }

    selectedCharger?.let { station ->
        ChargingStationSheet(station = station, onDismiss = { selectedCharger = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargingStationSheet(station: ChargingStation, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            station.address?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            station.operator?.let {
                Spacer(Modifier.height(8.dp))
                Text("Operator: $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            val connectors = station.connectors.joinToString(", ") { it.displayName }
            Text("Connectors: $connectors", style = MaterialTheme.typography.bodyMedium)
            station.distanceKm?.let {
                Spacer(Modifier.height(4.dp))
                Text("~%.1f km away".format(it), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
private fun LiveRideOverlayCard(viewModel: WheelViewModel, modifier: Modifier = Modifier) {
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val stats by viewModel.liveRideStats.collectAsStateWithLifecycle()
    val useMph = viewModel.appSettingsStore.getBool(AppSettingId.USE_MPH)

    val speedDisplay = DisplayUtils.convertSpeed(telemetry.speedKmh, useMph)
    val speedUnit = if (useMph) "mph" else "km/h"

    val distanceKm = (stats?.distanceMeters ?: 0L) / 1000.0
    val distanceValue = if (useMph) ByteUtils.kmToMiles(distanceKm) else distanceKm
    val distanceUnit = if (useMph) "mi" else "km"

    val elapsed = (stats?.elapsedMs ?: 0L) / 1000L
    val h = elapsed / 3600L
    val m = (elapsed % 3600L) / 60L
    val s = elapsed % 60L
    val timeStr = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(speedDisplay),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    speedUnit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Stat("Battery", "%.0f%%".format(telemetry.batteryLevel))
                Stat("PWM", "%.0f%%".format(telemetry.pwmPercent))
                Stat("Distance", "%.2f %s".format(distanceValue, distanceUnit))
                Stat("Time", timeStr)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
