package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.freewheel.core.replay.ReplayPosition
import org.freewheel.core.replay.ReplayState

@Composable
fun ReplayControls(
    replayState: ReplayState,
    position: ReplayPosition,
    speed: Float,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Timeline slider + time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTimeMs(position.currentTimeMs),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = position.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    formatTimeMs(position.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Controls row: play/pause, stop, speed chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (replayState == ReplayState.PLAYING) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (replayState == ReplayState.PLAYING) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Speed chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (mult in listOf(0.5f, 1f, 2f, 4f)) {
                        FilterChip(
                            selected = speed == mult,
                            onClick = { onSpeedChange(mult) },
                            label = { Text("${mult}x", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Packet progress
            Text(
                "Packet ${position.packetIndex} / ${position.totalPackets}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
