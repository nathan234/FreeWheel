package org.freewheel.compose.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.freewheel.core.domain.CommonLabels
import org.freewheel.core.domain.settings.ControlSpec
import org.freewheel.core.domain.settings.SettingsCommandId

/**
 * Confirmation dialog for dangerous wheel settings actions (calibrate, power off, lock, etc.).
 * Used by WheelSettingsScreen for wheel-side actions that require confirmation.
 */
@Composable
fun DangerousActionDialog(
    pendingAction: ControlSpec?,
    onDismiss: () -> Unit,
    onConfirmButton: (SettingsCommandId) -> Unit,
    onConfirmToggle: (SettingsCommandId) -> Unit
) {
    pendingAction?.let { action ->
        when (action) {
            is ControlSpec.DangerousButton -> {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text(action.confirmTitle) },
                    text = { Text(action.confirmMessage) },
                    confirmButton = {
                        TextButton(
                            onClick = { onConfirmButton(action.commandId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(CommonLabels.CONFIRM) }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text(CommonLabels.CANCEL) }
                    }
                )
            }
            is ControlSpec.DangerousToggle -> {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text(action.confirmTitle) },
                    text = { Text(action.confirmMessage) },
                    confirmButton = {
                        TextButton(
                            onClick = { onConfirmToggle(action.commandId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(CommonLabels.CONFIRM) }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text(CommonLabels.CANCEL) }
                    }
                )
            }
            else -> onDismiss()
        }
    }
}
