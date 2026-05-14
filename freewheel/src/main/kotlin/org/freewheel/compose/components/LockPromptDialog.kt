package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.LockPromptState

/**
 * Dialog driven by [WheelViewModel.lockPromptState]. Renders the four
 * non-Idle states: AwaitingPassword (input form), Sending (spinner),
 * Sent (auto-dismisses), Error (re-prompts the user with the reason).
 *
 * The dialog only mounts when the prompt is non-Idle, so the parent screen
 * can include it unconditionally without paying for an empty AlertDialog.
 *
 * Localization: error and label strings are defined inline here as English
 * literals; iOS rolls its own strings in SwiftUI. A future i18n pass should
 * lift them to a single resource file referenced by both platforms.
 */
@Composable
fun LockPromptDialog(viewModel: WheelViewModel) {
    val state by viewModel.lockPromptState.collectAsStateWithLifecycle()

    when (val current = state) {
        is LockPromptState.Idle -> Unit
        is LockPromptState.Sent -> {
            // Auto-dismiss the moment the wheel command has been queued.
            // Lock-state readback (subtype 5 byte 51) reconciles the toggle.
            LaunchedEffect(current) { viewModel.dismissLockPrompt() }
        }
        is LockPromptState.AwaitingPassword -> AwaitingPasswordDialog(
            state = current,
            onSubmit = { password, remember -> viewModel.submitLockPassword(password, remember) },
            onDismiss = { viewModel.dismissLockPrompt() },
        )
        is LockPromptState.Sending -> SendingDialog(state = current)
        is LockPromptState.Error -> ErrorDialog(
            state = current,
            onRetry = {
                // Re-derive AwaitingPassword from the same address/action so the
                // user can correct the input without re-opening from settings.
                viewModel.requestLock(current.action)
            },
            onDismiss = { viewModel.dismissLockPrompt() },
        )
    }
}

@Composable
private fun AwaitingPasswordDialog(
    state: LockPromptState.AwaitingPassword,
    onSubmit: (password: String, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(state.address) { mutableStateOf(state.prefilledPassword.orEmpty()) }
    // Default the "remember" toggle on when persistence is available AND a
    // password is already saved (so submitting an unchanged prefill doesn't
    // accidentally drop the saved entry). Off when no password is saved yet
    // so the first save is an explicit opt-in.
    var remember by remember(state.address) {
        mutableStateOf(state.canPersistPassword && state.prefilledPassword != null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.action == LockPromptState.LockAction.LOCK) "Lock wheel" else "Unlock wheel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.action == LockPromptState.LockAction.LOCK)
                        "Enter your wheel password to lock the motor."
                    else
                        "Enter your wheel password to unlock the motor."
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.canPersistPassword) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Remember password")
                        Switch(checked = remember, onCheckedChange = { remember = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password, remember) },
                enabled = password.isNotBlank(),
            ) { Text(if (state.action == LockPromptState.LockAction.LOCK) "Lock" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SendingDialog(state: LockPromptState.Sending) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (state.action == LockPromptState.LockAction.LOCK) "Locking…" else "Unlocking…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                Text("Sending command to the wheel.")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ErrorDialog(
    state: LockPromptState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (state.reason) {
        LockPromptState.ErrorReason.EMPTY_PASSWORD -> "Password can't be empty."
        LockPromptState.ErrorReason.INVALID_FORMAT -> "Password must be a number up to 16777215."
        LockPromptState.ErrorReason.NOT_CONNECTED -> "The wheel disconnected. Reconnect and try again."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.action == LockPromptState.LockAction.LOCK) "Couldn't lock" else "Couldn't unlock") },
        text = { Text(message) },
        confirmButton = {
            // NOT_CONNECTED can't be retried until the wheel comes back; the
            // other reasons are fixable by re-entering the password.
            if (state.reason != LockPromptState.ErrorReason.NOT_CONNECTED) {
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
