package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.PasswordManagementInput
import org.freewheel.core.domain.PasswordManagementState
import org.freewheel.core.domain.requiresNewPassword
import org.freewheel.core.domain.requiresOldPassword

/**
 * Dialog driven by [WheelViewModel.passwordManagementState]. Renders one of
 * six variants: Idle (nothing), Editing (form), Submitting/PendingAck (spinner),
 * Confirmed (success toast that auto-dismisses), Failed (error with retry).
 *
 * The form fields depend on the [PasswordManagementState.Operation]:
 *   - SET: new password + confirmation (+ remember toggle)
 *   - MODIFY: current password, new password + confirmation (+ remember toggle)
 *   - CLEAR: current password
 *   - AUTO_LOCK_ON / AUTO_LOCK_OFF: current password
 *
 * Auto-dismiss is identical to the lock-prompt pattern in
 * [LockPromptDialog]: a LaunchedEffect closes the dialog once the wheel has
 * confirmed. The ViewModel owns the ack listener so this Composable stays
 * dumb — it only renders the current state and forwards user input.
 */
@Composable
fun PasswordManagementDialog(viewModel: WheelViewModel) {
    val state by viewModel.passwordManagementState.collectAsStateWithLifecycle()

    when (val current = state) {
        is PasswordManagementState.Idle -> Unit
        is PasswordManagementState.Editing -> EditingDialog(
            state = current,
            onSubmit = { input -> viewModel.submitPasswordManagement(input) },
            onDismiss = { viewModel.dismissPasswordManagement() },
        )
        is PasswordManagementState.Submitting -> WaitingDialog(operation = current.operation)
        is PasswordManagementState.PendingAck -> WaitingDialog(operation = current.operation)
        is PasswordManagementState.Confirmed -> ConfirmedDialog(
            state = current,
            onDismiss = { viewModel.dismissPasswordManagement() },
        )
        is PasswordManagementState.Failed -> FailedDialog(
            state = current,
            onRetry = { viewModel.requestPasswordManagement(current.operation) },
            onDismiss = { viewModel.dismissPasswordManagement() },
        )
    }
}

@Composable
private fun EditingDialog(
    state: PasswordManagementState.Editing,
    onSubmit: (PasswordManagementInput) -> Unit,
    onDismiss: () -> Unit,
) {
    val showOld = state.operation.requiresOldPassword
    val showNew = state.operation.requiresNewPassword
    val showRemember = showNew && state.canPersistPassword

    // Prefill the current/old password from the store so the user doesn't
    // have to retype something they've already authorized once.
    var oldPwd by remember(state.address, state.operation) {
        mutableStateOf(if (showOld) state.priorStoredPassword.orEmpty() else "")
    }
    var newPwd by remember(state.address, state.operation) { mutableStateOf("") }
    var confirmPwd by remember(state.address, state.operation) { mutableStateOf("") }
    // Default the remember toggle on when the wheel already has a stored
    // password (MODIFY/CLEAR) so unchecking is an explicit opt-out — matches
    // the lock-prompt default.
    var rememberNew by remember(state.address, state.operation) {
        mutableStateOf(state.canPersistPassword && state.priorStoredPassword != null)
    }

    val submitEnabled = when {
        showOld && oldPwd.isBlank() -> false
        showNew && (newPwd.isBlank() || confirmPwd.isBlank()) -> false
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(state.operation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(descriptionFor(state.operation))
                if (showOld) {
                    PasswordField(
                        label = if (showNew) "Current password" else "Password",
                        value = oldPwd,
                        onValueChange = { oldPwd = it.filter { ch -> ch.isDigit() }.take(8) },
                    )
                }
                if (showNew) {
                    PasswordField(
                        label = "New password",
                        value = newPwd,
                        onValueChange = { newPwd = it.filter { ch -> ch.isDigit() }.take(8) },
                    )
                    PasswordField(
                        label = "Confirm new password",
                        value = confirmPwd,
                        onValueChange = { confirmPwd = it.filter { ch -> ch.isDigit() }.take(8) },
                    )
                }
                if (showRemember) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Remember new password")
                        Switch(checked = rememberNew, onCheckedChange = { rememberNew = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        PasswordManagementInput(
                            oldPassword = oldPwd,
                            newPassword = newPwd,
                            confirmationPassword = confirmPwd,
                            rememberNewPassword = rememberNew,
                        ),
                    )
                },
                enabled = submitEnabled,
            ) { Text(confirmLabelFor(state.operation)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WaitingDialog(operation: PasswordManagementState.Operation) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${titleFor(operation)}…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                Text("Waiting for wheel confirmation.")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ConfirmedDialog(
    state: PasswordManagementState.Confirmed,
    onDismiss: () -> Unit,
) {
    // Hold the success message briefly so the user actually sees it before
    // the dialog clears. 1.2s matches the toast duration the user is used to
    // from the lock prompt's Sent transition (which is effectively instant
    // there because there's no ack to wait on).
    LaunchedEffect(state) {
        delay(1200L)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(successTitleFor(state.operation)) },
        text = { Text(successMessageFor(state.operation)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun FailedDialog(
    state: PasswordManagementState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (state.reason) {
        PasswordManagementState.FailureReason.EMPTY_PASSWORD ->
            "Password can't be empty."
        PasswordManagementState.FailureReason.INVALID_FORMAT ->
            "Password must be a number up to 16777215."
        PasswordManagementState.FailureReason.PASSWORDS_DO_NOT_MATCH ->
            "The confirmation doesn't match the new password."
        PasswordManagementState.FailureReason.NOT_CONNECTED ->
            "The wheel disconnected. Reconnect and try again."
        PasswordManagementState.FailureReason.WRONG_PASSWORD ->
            "The wheel rejected the command — check the current password."
        PasswordManagementState.FailureReason.TIMEOUT ->
            "The wheel didn't confirm in time. Try again."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(failureTitleFor(state.operation)) },
        text = { Text(message) },
        confirmButton = {
            // NOT_CONNECTED can't be retried until the wheel comes back.
            if (state.reason != PasswordManagementState.FailureReason.NOT_CONNECTED) {
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun titleFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET -> "Set lock password"
    PasswordManagementState.Operation.MODIFY -> "Change lock password"
    PasswordManagementState.Operation.CLEAR -> "Clear lock password"
    PasswordManagementState.Operation.AUTO_LOCK_ON -> "Enable auto-lock"
    PasswordManagementState.Operation.AUTO_LOCK_OFF -> "Disable auto-lock"
}

private fun descriptionFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET ->
        "Choose a numeric password the wheel will require for lock/unlock."
    PasswordManagementState.Operation.MODIFY ->
        "Enter your current password, then a new one."
    PasswordManagementState.Operation.CLEAR ->
        "Enter your current password to remove it from the wheel."
    PasswordManagementState.Operation.AUTO_LOCK_ON ->
        "Enter your current password to enable auto-lock."
    PasswordManagementState.Operation.AUTO_LOCK_OFF ->
        "Enter your current password to disable auto-lock."
}

private fun confirmLabelFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET -> "Set"
    PasswordManagementState.Operation.MODIFY -> "Change"
    PasswordManagementState.Operation.CLEAR -> "Clear"
    PasswordManagementState.Operation.AUTO_LOCK_ON -> "Enable"
    PasswordManagementState.Operation.AUTO_LOCK_OFF -> "Disable"
}

private fun successTitleFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET -> "Password set"
    PasswordManagementState.Operation.MODIFY -> "Password changed"
    PasswordManagementState.Operation.CLEAR -> "Password cleared"
    PasswordManagementState.Operation.AUTO_LOCK_ON -> "Auto-lock enabled"
    PasswordManagementState.Operation.AUTO_LOCK_OFF -> "Auto-lock disabled"
}

private fun successMessageFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET ->
        "The wheel will now require this password to lock or unlock."
    PasswordManagementState.Operation.MODIFY ->
        "Use the new password from now on."
    PasswordManagementState.Operation.CLEAR ->
        "The wheel no longer requires a password to lock or unlock."
    PasswordManagementState.Operation.AUTO_LOCK_ON ->
        "The wheel will lock itself automatically."
    PasswordManagementState.Operation.AUTO_LOCK_OFF ->
        "The wheel won't lock itself automatically anymore."
}

private fun failureTitleFor(operation: PasswordManagementState.Operation): String = when (operation) {
    PasswordManagementState.Operation.SET -> "Couldn't set password"
    PasswordManagementState.Operation.MODIFY -> "Couldn't change password"
    PasswordManagementState.Operation.CLEAR -> "Couldn't clear password"
    PasswordManagementState.Operation.AUTO_LOCK_ON -> "Couldn't enable auto-lock"
    PasswordManagementState.Operation.AUTO_LOCK_OFF -> "Couldn't disable auto-lock"
}
