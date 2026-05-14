package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.PasswordManagementState
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.autoLockEnabled
import org.freewheel.core.domain.hasPassword

/**
 * Veteran lock-password entry card. Mirrors the four rows in the official
 * Leaperkim app's Lock Settings screen (LockSettingActivity).
 *
 * Visibility is driven by [WheelSettings.Veteran.hasPassword]:
 *   - null → card hidden entirely (we haven't read a subtype-5 frame yet,
 *     so we don't know which row to show)
 *   - false → only the "Set lock password" row
 *   - true  → "Change password" + "Clear password" + auto-lock toggle
 *
 * All four actions open the [PasswordManagementDialog]; the dialog handles
 * input, validation, ack, and persistence.
 */
@Composable
fun VeteranPasswordCard(viewModel: WheelViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val veteran = settings as? WheelSettings.Veteran ?: return
    val hasPassword = veteran.hasPassword ?: return
    val autoLockOn = veteran.autoLockEnabled ?: false

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Lock password",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (hasPassword)
                    "The wheel currently requires a password to lock or unlock."
                else
                    "Set a numeric password the wheel will require for lock/unlock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (!hasPassword) {
                ActionRow("Set lock password") {
                    viewModel.requestPasswordManagement(PasswordManagementState.Operation.SET)
                }
            } else {
                ActionRow("Change password") {
                    viewModel.requestPasswordManagement(PasswordManagementState.Operation.MODIFY)
                }
                ActionRow("Clear password") {
                    viewModel.requestPasswordManagement(PasswordManagementState.Operation.CLEAR)
                }
                AutoLockRow(
                    enabled = autoLockOn,
                    onToggle = { newEnabled ->
                        // Mirror the app's toggle semantics: send the action that flips the
                        // current state. The dialog collects the current password and
                        // confirms with the wheel before applying the visual change.
                        val op = if (newEnabled)
                            PasswordManagementState.Operation.AUTO_LOCK_ON
                        else
                            PasswordManagementState.Operation.AUTO_LOCK_OFF
                        viewModel.requestPasswordManagement(op)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun AutoLockRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Auto-lock", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
