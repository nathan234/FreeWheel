package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freewheel.core.domain.RidesLabels
import org.freewheel.core.telemetry.ReplayPlaybackReducer
import org.freewheel.core.telemetry.ReplayPlaybackState
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.utils.DisplayUtils

/**
 * Compose-facing wrapper around the shared [ReplayPlaybackReducer]. All state transitions are
 * delegated to the reducer; this class only owns the Compose-observable container and the
 * coroutine that drives the playback clock.
 */
@Stable
class CsvReplayController(
    val samples: List<TelemetrySample>
) {
    private var state by mutableStateOf(ReplayPlaybackState())
    private var playJob: Job? = null

    val isPlaying: Boolean get() = state.isPlaying
    val currentIndex: Int get() = state.currentIndex
    val speedMultiplier: Float get() = state.speedMultiplier
    val isFinished: Boolean get() = state.isFinished

    val currentSample: TelemetrySample?
        get() = ReplayPlaybackReducer.currentSample(state, samples)

    val progress: Float
        get() = ReplayPlaybackReducer.progress(state, samples)

    val totalDurationMs: Long
        get() = ReplayPlaybackReducer.totalDurationMs(samples)

    val elapsedMs: Long
        get() = ReplayPlaybackReducer.elapsedMs(state, samples)

    fun play(scope: CoroutineScope) {
        if (state.isPlaying) return
        state = ReplayPlaybackReducer.play(state)
        playJob = scope.launch {
            while (isActive && state.isPlaying) {
                val tick = ReplayPlaybackReducer.advanceOne(state, samples)
                delay(tick.delayMs)
                state = tick.state
            }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        state = ReplayPlaybackReducer.pause(state)
    }

    fun togglePlayPause(scope: CoroutineScope) {
        if (state.isPlaying) pause() else play(scope)
    }

    fun seekTo(progress: Float) {
        if (state.isPlaying) pause()
        state = ReplayPlaybackReducer.seekTo(state, progress, samples)
    }

    fun skipForward(scope: CoroutineScope) {
        val wasPlaying = state.isPlaying
        if (wasPlaying) pause()
        state = ReplayPlaybackReducer.skipForward(state, samples)
        if (wasPlaying) play(scope)
    }

    fun skipBackward(scope: CoroutineScope) {
        val wasPlaying = state.isPlaying
        if (wasPlaying) pause()
        state = ReplayPlaybackReducer.skipBackward(state, samples)
        if (wasPlaying) play(scope)
    }

    fun setSpeed(multiplier: Float, scope: CoroutineScope) {
        val wasPlaying = state.isPlaying
        if (wasPlaying) pause()
        state = ReplayPlaybackReducer.setSpeed(state, multiplier)
        if (wasPlaying) play(scope)
    }

    fun stop() {
        pause()
        state = ReplayPlaybackReducer.stop(state)
    }
}

@Composable
fun RideReplayControls(
    controller: CsvReplayController,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Time + slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    DisplayUtils.formatDurationCompact((controller.elapsedMs / 1000).toInt()),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = controller.progress,
                    onValueChange = { controller.seekTo(it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    DisplayUtils.formatDurationCompact((controller.totalDurationMs / 1000).toInt()),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Controls: skip back, play/pause, skip forward, speed chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.skipBackward(scope) }) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription = "Skip back 30s",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { controller.togglePlayPause(scope) }) {
                        Icon(
                            if (controller.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (controller.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = { controller.skipForward(scope) }) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription = "Skip forward 30s",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (mult in listOf(1f, 2f, 4f, 8f)) {
                        FilterChip(
                            selected = controller.speedMultiplier == mult,
                            onClick = { controller.setSpeed(mult, scope) },
                            label = {
                                Text(
                                    "${mult.toInt()}x",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReplayStatsPanel(
    sample: TelemetrySample,
    useMph: Boolean,
    useFahrenheit: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ReplayStatItem(
                    label = RidesLabels.SPEED_LABEL,
                    value = DisplayUtils.formatSpeed(sample.speedKmh, useMph)
                )
                ReplayStatItem(
                    label = RidesLabels.VOLTAGE_LABEL,
                    value = "%.1f V".format(sample.voltageV)
                )
                ReplayStatItem(
                    label = RidesLabels.BATTERY_LABEL,
                    value = "%.0f%%".format(sample.batteryPercent)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ReplayStatItem(
                    label = RidesLabels.CURRENT_LABEL,
                    value = "%.1f A".format(sample.currentA)
                )
                ReplayStatItem(
                    label = RidesLabels.POWER_LABEL,
                    value = "%.0f W".format(sample.powerW)
                )
                ReplayStatItem(
                    label = RidesLabels.TEMP_LABEL,
                    value = DisplayUtils.formatTemperature(sample.temperatureC, useFahrenheit)
                )
                ReplayStatItem(
                    label = RidesLabels.PWM_LABEL,
                    value = "%.0f%%".format(sample.pwmPercent)
                )
            }
        }
    }
}

@Composable
private fun ReplayStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ==================== Ride Stats Header ====================

@Composable
fun RideStatsHeader(
    startTimeMs: Long,
    endTimeMs: Long?,
    durationSeconds: Int,
    distanceKm: Double,
    maxSpeedKmh: Double,
    maxPwmPercent: Double?,
    useMph: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeaderStatItem(RidesLabels.START_TIME, formatEpochAsTime(startTimeMs))
                if (endTimeMs != null) {
                    HeaderStatItem(RidesLabels.END_TIME, formatEpochAsTime(endTimeMs))
                }
                HeaderStatItem(RidesLabels.DURATION, DisplayUtils.formatDurationCompact(durationSeconds))
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeaderStatItem(RidesLabels.TOP_SPEED, DisplayUtils.formatSpeed(maxSpeedKmh, useMph))
                HeaderStatItem(RidesLabels.DISTANCE, DisplayUtils.formatDistance(distanceKm, useMph))
                if (maxPwmPercent != null) {
                    HeaderStatItem(RidesLabels.MAX_PWM, "%.0f%%".format(maxPwmPercent))
                }
            }
        }
    }
}

@Composable
private fun HeaderStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatEpochAsTime(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMs))
}

