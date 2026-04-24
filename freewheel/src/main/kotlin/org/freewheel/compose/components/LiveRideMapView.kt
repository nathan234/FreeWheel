package org.freewheel.compose.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StrokeStyle
import com.google.android.gms.maps.model.StyleSpan
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import org.freewheel.core.location.ChargingStation
import org.freewheel.core.logging.RoutePoint
import org.freewheel.core.logging.SpeedRange

/**
 * Live-ride Google Maps view. Shows the platform blue user-dot plus a
 * speed-colored polyline that grows as the ride proceeds. In follow mode,
 * the camera tracks [currentLatLng]. User-initiated pan/zoom flips
 * [onUserPanned] so the parent can hide follow mode.
 */
@Composable
fun LiveRideMapView(
    routePoints: List<RoutePoint>,
    speedRange: SpeedRange?,
    currentLatLng: LatLng?,
    followMode: Boolean,
    onUserPanned: () -> Unit,
    modifier: Modifier = Modifier,
    chargers: List<ChargingStation> = emptyList(),
    onChargerTap: (ChargingStation) -> Unit = {},
    onCameraIdle: (LatLng) -> Unit = {}
) {
    val cameraPositionState = rememberCameraPositionState()

    val latLngList = remember(routePoints) {
        routePoints.map { LatLng(it.latitude, it.longitude) }
    }

    val colorSpans = remember(routePoints, speedRange) {
        buildSpeedSpans(routePoints, speedRange)
    }

    val startIcon = remember { dotIcon(0xFF4CAF50.toInt(), 20) }

    // Follow user when in follow mode
    LaunchedEffect(followMode, currentLatLng) {
        if (followMode && currentLatLng != null) {
            val current = cameraPositionState.position
            val targetZoom = if (current.zoom < 13f) 16f else current.zoom
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(currentLatLng)
                        .zoom(targetZoom)
                        .build()
                ),
                500
            )
        }
    }

    // Detect user gestures → exit follow mode
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            onUserPanned()
        }
    }

    // Report camera-idle target so the parent can refresh nearby POIs
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            onCameraIdle(cameraPositionState.position.target)
        }
    }

    val chargerIcon = remember {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
    }

    val uiSettings = remember {
        MapUiSettings(
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            zoomControlsEnabled = false
        )
    }

    val properties = remember {
        MapProperties(isMyLocationEnabled = true)
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        uiSettings = uiSettings,
        properties = properties
    ) {
        if (latLngList.size >= 2) {
            Polyline(
                points = latLngList,
                spans = colorSpans,
                width = 10f
            )
        }
        latLngList.firstOrNull()?.let {
            Marker(
                state = MarkerState(position = it),
                icon = startIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                flat = true,
                zIndex = 1f
            )
        }
        chargers.forEach { station ->
            Marker(
                state = MarkerState(position = LatLng(station.latitude, station.longitude)),
                title = station.name,
                snippet = station.address,
                icon = chargerIcon,
                onClick = {
                    onChargerTap(station)
                    false
                }
            )
        }
    }
}

// MARK: - Speed gradient spans

/** Builds per-segment color spans matching the trip-detail gradient (green→yellow→red). */
private fun buildSpeedSpans(points: List<RoutePoint>, range: SpeedRange?): List<StyleSpan> {
    if (points.size < 2) return emptyList()
    val minSpeed = range?.min ?: 0.0
    val maxSpeed = range?.max ?: 0.0
    val spread = maxSpeed - minSpeed

    return points.zipWithNext().map { (a, _) ->
        val fraction = if (spread > 0) (a.speedKmh - minSpeed) / spread else 0.0
        StyleSpan(StrokeStyle.colorBuilder(speedColor(fraction)).build())
    }
}

/** Green (0.0) → Yellow (0.5) → Red (1.0). */
private fun speedColor(fraction: Double): Int {
    val clamped = fraction.coerceIn(0.0, 1.0)
    val r: Float
    val g: Float
    if (clamped < 0.5) {
        val t = (clamped * 2).toFloat()
        r = t
        g = 1f
    } else {
        val t = ((clamped - 0.5) * 2).toFloat()
        r = 1f
        g = 1f - t
    }
    return android.graphics.Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), 0)
}

private fun dotIcon(color: Int, sizePx: Int): BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
