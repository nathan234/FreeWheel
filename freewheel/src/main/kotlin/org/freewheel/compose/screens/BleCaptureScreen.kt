package org.freewheel.compose.screens

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.utils.PlatformDateFormatter
import java.io.File

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/FreeWheel/Views/BleCaptureView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Title header
//  2. Capture status bar (packet counts, elapsed time)
//  3. Start/Stop button
//  4. Marker input (visible when capturing)
//  5. Quick-marker buttons (send command + insert marker)
//  6. Capture history with share/delete

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BleCaptureScreen(
    viewModel: WheelViewModel,
    onBack: () -> Unit = {},
    onReplay: () -> Unit = {}
) {
    val context = LocalContext.current
    val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
    val captureStats by viewModel.captureStats.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState.isConnected

    var markerText by remember { mutableStateOf("") }
    var captureFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    // Elapsed time ticker
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isCapturing, captureStats.startTimeMs) {
        if (isCapturing && captureStats.startTimeMs > 0) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - captureStats.startTimeMs) / 1000
                delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    // Load capture files
    LaunchedEffect(isCapturing) {
        captureFiles = loadCaptureFiles(viewModel)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 16.dp)
    ) {
        Text(
            "BLE Capture",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Status bar
        if (isCapturing) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("RX: ${captureStats.rxCount}", style = MaterialTheme.typography.bodyLarge)
                        Text("TX: ${captureStats.txCount}", style = MaterialTheme.typography.bodyLarge)
                        Text("Markers: ${captureStats.markerCount}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Elapsed: ${formatElapsedSeconds(elapsedSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Start/Stop button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (isCapturing) {
                Button(
                    onClick = {
                        viewModel.stopCapture()
                        captureFiles = loadCaptureFiles(viewModel)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Capture")
                }
            } else {
                Button(
                    onClick = { viewModel.startCapture() }
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Capture")
                }
            }
        }

        // Marker input (visible when capturing)
        if (isCapturing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = markerText,
                    onValueChange = { markerText = it },
                    label = { Text("Marker label") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (markerText.isNotBlank()) {
                            viewModel.insertCaptureMarker(markerText.trim())
                            markerText = ""
                        }
                    },
                    enabled = markerText.isNotBlank()
                ) {
                    Text("Add")
                }
            }

            // Quick-marker buttons
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        viewModel.toggleLight()
                        viewModel.insertCaptureMarker("toggled light")
                    },
                    label = { Text("Toggle Light") }
                )
                AssistChip(
                    onClick = {
                        viewModel.wheelBeep()
                        viewModel.insertCaptureMarker("beep")
                    },
                    label = { Text("Beep") }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // Copy Diagnostic Info button
        Button(
            onClick = {
                val text = viewModel.buildDiagnosticText()
                if (text != null) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Info", text))
                    Toast.makeText(context, "Diagnostic info copied", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = isConnected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy Diagnostic Info")
        }

        // Capture history
        Text(
            "Capture History",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (captureFiles.isEmpty()) {
            Text(
                "No captures yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(captureFiles, key = { it.name }) { file ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteCaptureFile(file.name)
                                captureFiles = captureFiles.filter { it.name != file.name }
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
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false
                    ) {
                        CaptureRow(
                            file = file,
                            onShare = {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Capture"))
                            },
                            onReplay = {
                                viewModel.startReplay(file)
                                onReplay()
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
private fun CaptureRow(
    file: File,
    onShare: () -> Unit,
    onReplay: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onReplay)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = PlatformDateFormatter.formatFriendlyDate(file.lastModified()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            val sizeKb = file.length() / 1024
            Text(
                text = "${sizeKb} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShare) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun loadCaptureFiles(viewModel: WheelViewModel): List<File> {
    val dir = viewModel.getCapturesDir()
    return dir.listFiles()
        ?.filter { it.extension == "csv" }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

private fun formatElapsedSeconds(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
