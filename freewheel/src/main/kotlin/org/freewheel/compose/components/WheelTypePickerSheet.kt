package org.freewheel.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.identity.WheelType

/**
 * Bottom sheet shown when [org.freewheel.core.service.ConnectionState.WheelTypeRequired]
 * fires — i.e. the topology fingerprint matcher and name detector both miss
 * and no SAVED_PROFILE / EXPLICIT hint exists. The user picks a wheel type;
 * confirmation runs [org.freewheel.core.service.WcmEffect.ConfigureBle]
 * against the still-live peripheral and proceeds to Connected without a
 * reconnect. Dismissing without picking tears down the BLE session.
 *
 * Per the Pass 4 plan guardrails:
 *  - The "Likely" chip on the option matched by
 *    [WheelTypeDetector.deriveTypeFromName] is informational only — never a
 *    silent auto-pick. The user has to tap the row to confirm.
 *  - There is no timeout-driven auto-confirm; dismissal disconnects.
 *  - GOTWAY_VIRTUAL / Unknown are intentionally absent from the option list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelTypePickerSheet(
    deviceName: String?,
    onConfirm: (WheelType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val likelyType = WheelTypeDetector.deriveTypeFromName(deviceName)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Select wheel type",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val nameSuffix = deviceName?.takeIf { it.isNotBlank() }?.let { " for \"$it\"" } ?: ""
            Text(
                text = "We couldn't auto-detect the protocol$nameSuffix. " +
                    "Pick the matching wheel type to finish connecting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PICKABLE_WHEEL_TYPES.forEach { type ->
                WheelTypeRow(
                    type = type,
                    isLikely = type == likelyType,
                    onClick = { onConfirm(type) },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun WheelTypeRow(
    type: WheelType,
    isLikely: Boolean,
    onClick: () -> Unit,
) {
    if (isLikely) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(pickerLabel(type))
                AssistChip(
                    onClick = onClick,
                    label = { Text("Likely") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(pickerLabel(type))
        }
    }
}

private val PICKABLE_WHEEL_TYPES = listOf(
    WheelType.KINGSONG,
    WheelType.GOTWAY,
    WheelType.VETERAN,
    WheelType.LEAPERKIM,
    WheelType.INMOTION,
    WheelType.LORIN,
    WheelType.NINEBOT,
    WheelType.NINEBOT_Z,
)

/**
 * Picker-specific labels. [WheelType.displayName] collapses protocol pairs
 * (e.g. NINEBOT / NINEBOT_Z both → "Ninebot"); the picker has to disambiguate
 * so the user can tell legacy/V1 apart from V2/Z protocols.
 */
private fun pickerLabel(type: WheelType): String = when (type) {
    WheelType.KINGSONG -> "KingSong"
    WheelType.GOTWAY -> "Begode / Gotway"
    WheelType.VETERAN -> "Veteran (Sherman/Lynx/Patton)"
    WheelType.LEAPERKIM -> "Leaperkim CAN (newer firmware)"
    WheelType.INMOTION -> "InMotion V1 (V8/V10/V11)"
    WheelType.LORIN -> "InMotion Lorin (V12+, P6)"
    WheelType.NINEBOT -> "Ninebot (legacy)"
    WheelType.NINEBOT_Z -> "Ninebot Z (Z10+)"
    WheelType.GOTWAY_VIRTUAL,
    WheelType.Unknown -> error("$type is not a valid pick")
}
