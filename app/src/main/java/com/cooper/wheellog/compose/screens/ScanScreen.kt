package com.cooper.wheellog.compose.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.compose.WheelViewModel
import com.cooper.wheellog.core.domain.CommonLabels
import com.cooper.wheellog.core.domain.ScanLabels
import com.cooper.wheellog.core.utils.DisplayUtils

// CROSS-PLATFORM SYNC: This screen mirrors iosApp/WheelLog/Views/ScanView.swift.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Auto-reconnect overlay (Android: separate AutoConnectContent; iOS: inline in ScanView)
//  2. Header with app name
//  3. Scan button with pulse animation
//  4. Device list: "My Wheels" section (saved devices)
//  5. Device list: "New Devices" section (unsaved, swipe-to-delete to forget)
//  6. Empty state with demo mode button
//  Note: iOS has additional simulator-specific test UI buttons

@Composable
fun AutoConnectContent(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = ScanLabels.RECONNECTING,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) {
            Text(CommonLabels.CANCEL, color = Color(0xFFF44336), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ScanScreen(viewModel: WheelViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val savedAddresses by viewModel.savedAddresses.collectAsState()
    val hasDevices = devices.isNotEmpty()
    val connectingAddress = connectionState.connectingAddress
    val failedAddress = connectionState.failedAddress

    // Partition devices into saved ("My Wheels") and new
    val myWheels = devices.filter { it.address in savedAddresses }
    val newDevices = devices.filter { it.address !in savedAddresses }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Header
        Text(
            text = ScanLabels.APP_NAME,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        // Hide scan button while connecting
        if (connectingAddress == null) {
            ScanButton(
                isScanning = isScanning,
                hasDevices = hasDevices,
                onToggleScan = {
                    if (isScanning) viewModel.stopScan() else viewModel.startScan()
                }
            )
        }

        if (hasDevices) {
            // Device list with My Wheels / New Devices sections
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                // "My Wheels" section — only shown if any saved devices are advertising
                if (myWheels.isNotEmpty()) {
                    item {
                        Text(
                            text = ScanLabels.MY_WHEELS,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(myWheels, key = { "saved_${it.address}" }) { device ->
                        val isThisConnecting = connectingAddress == device.address
                        val isThisFailed = failedAddress == device.address
                        val savedName = viewModel.getSavedDisplayName(device.address)
                        SwipeToDismissDeviceRow(
                            onDismiss = { viewModel.forgetProfile(device.address) }
                        ) {
                            DeviceRow(
                                device = device,
                                displayNameOverride = savedName,
                                isConnecting = isThisConnecting,
                                isFailed = isThisFailed,
                                statusText = if (isThisConnecting) connectionState.statusText else null,
                                isDisabled = connectingAddress != null && !isThisConnecting,
                                onClick = { viewModel.connect(device.address) },
                                onCancel = if (isThisConnecting) {
                                    { viewModel.disconnect() }
                                } else null
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                // "New Devices" section
                if (newDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = ScanLabels.NEW_DEVICES,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(newDevices, key = { "new_${it.address}" }) { device ->
                        val isThisConnecting = connectingAddress == device.address
                        val isThisFailed = failedAddress == device.address
                        DeviceRow(
                            device = device,
                            isConnecting = isThisConnecting,
                            isFailed = isThisFailed,
                            statusText = if (isThisConnecting) connectionState.statusText else null,
                            isDisabled = connectingAddress != null && !isThisConnecting,
                            onClick = { viewModel.connect(device.address) },
                            onCancel = if (isThisConnecting) {
                                { viewModel.disconnect() }
                            } else null
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                if (isScanning) {
                    item {
                        Text(
                            text = ScanLabels.SCANNING,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        } else {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isScanning) ScanLabels.SEARCHING else ScanLabels.TAP_TO_SEARCH,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(32.dp))

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Demo mode button
                Button(
                    onClick = { viewModel.startDemo() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(ScanLabels.START_DEMO)
                }
            }
        }
    }
}

@Composable
private fun ScanButton(
    isScanning: Boolean,
    hasDevices: Boolean,
    onToggleScan: () -> Unit
) {
    val size = if (hasDevices) 100.dp else 160.dp
    val buttonColor = if (isScanning) Color(0xFFF44336) else Color(0xFF2196F3)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (hasDevices) 12.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulse animation when scanning
        if (isScanning) {
            val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse_scale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse_alpha"
            )
            Box(
                modifier = Modifier
                    .size(size + 20.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = alpha))
            )
        }

        // Main button
        Box(
            modifier = Modifier
                .size(size)
                .shadow(12.dp, CircleShape, ambientColor = buttonColor.copy(alpha = 0.4f))
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(onClick = onToggleScan),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (hasDevices) 4.dp else 8.dp)
            ) {
                Icon(
                    imageVector = when {
                        isScanning -> Icons.Default.Stop
                        hasDevices -> Icons.Default.Refresh
                        else -> Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (hasDevices) 24.dp else 40.dp)
                )
                Text(
                    text = when {
                        isScanning -> CommonLabels.CANCEL
                        hasDevices -> ScanLabels.RESCAN
                        else -> ScanLabels.SCAN
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (hasDevices) 14.sp else 20.sp
                )
            }
        }
    }
}

@Composable
private fun SwipeToDismissDeviceRow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDismiss()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF44336))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = ScanLabels.FORGET,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            content()
        }
    }
}

@Composable
private fun DeviceRow(
    device: WheelViewModel.DiscoveredDevice,
    displayNameOverride: String? = null,
    isConnecting: Boolean = false,
    isFailed: Boolean = false,
    statusText: String? = null,
    isDisabled: Boolean = false,
    onClick: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val rowBackground = when {
        isConnecting -> Color(0xFF2196F3).copy(alpha = 0.08f)
        isFailed -> Color(0xFFF44336).copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .then(
                if (isDisabled) Modifier.alpha(0.4f)
                else Modifier
            )
            .clickable(enabled = !isDisabled && !isConnecting, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayNameOverride?.ifEmpty { null }
                    ?: device.name.ifEmpty { ScanLabels.UNKNOWN_DEVICE },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) Color(0xFFF44336) else Color(0xFF2196F3),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (isFailed) {
                Text(
                    text = ScanLabels.CONNECTION_FAILED,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF44336),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (isConnecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color(0xFF2196F3),
                    strokeWidth = 2.dp
                )
                if (onCancel != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF44336))
                    ) {
                        Text(CommonLabels.CANCEL, fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else if (!isDisabled) {
            Column(horizontalAlignment = Alignment.End) {
                SignalStrengthBars(rssi = device.rssi)
                Text(
                    text = DisplayUtils.signalDescription(device.rssi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SignalStrengthBars(rssi: Int) {
    val bars = DisplayUtils.signalBars(rssi)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until 4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (i < bars) Color(0xFF4CAF50)
                        else Color.Gray.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

