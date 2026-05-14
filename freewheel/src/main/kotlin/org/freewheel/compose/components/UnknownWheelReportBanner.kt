package org.freewheel.compose.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.wheel.WheelCatalog
import org.freewheel.core.domain.identity.wheel.WheelReport

/**
 * Surfaces when the live connected wheel does not match any [WheelCatalog] entry.
 * Tapping "Report" opens a pre-populated GitHub issue in the browser; the user
 * reviews and submits there. No data leaves the device unless the user clicks
 * Submit on GitHub itself.
 *
 * The optional [discoveredServices] is the live GATT topology surfaced by
 * [WheelConnectionManager.discoveredServices] — when non-null it's
 * forwarded to [WheelReport] so the GitHub issue includes the full
 * service+characteristic dump and a maintainer can paste it straight
 * into `WheelTopologies.ALL`.
 */
@Composable
fun UnknownWheelReportBanner(
    identity: WheelIdentity,
    observedMaxKmh: Double,
    appVersion: String,
    isConnected: Boolean,
    discoveredServices: DiscoveredServices? = null,
    modifier: Modifier = Modifier,
) {
    val isUnknown = remember(identity, isConnected) {
        isConnected &&
            identity.wheelType.name != "Unknown" &&
            (identity.btName.isNotEmpty() || identity.model.isNotEmpty() || identity.version.isNotEmpty()) &&
            WheelCatalog.match(identity.wheelType, identity) == null
    }
    var dismissed by remember(identity.btName, identity.model, identity.version) { mutableStateOf(false) }
    val show = isUnknown && !dismissed
    val context = LocalContext.current

    AnimatedVisibility(
        visible = show,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(12.dp),
        ) {
            Text(
                text = "Unrecognized wheel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            val displayName = identity.btName.ifEmpty { identity.model.ifEmpty { identity.version } }
            Text(
                text = "Help us add \"$displayName\" to the catalog so the speedometer scales correctly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { dismissed = true }) { Text("Dismiss") }
                TextButton(onClick = {
                    val topology = discoveredServices?.services.orEmpty().map { svc ->
                        ServiceTopology(svc.uuid, svc.characteristics.toSet())
                    }
                    val url = WheelReport.buildGitHubIssueUrl(
                        identity = identity,
                        observedMaxKmh = observedMaxKmh,
                        appVersion = appVersion,
                        appPlatform = "android",
                        services = topology,
                    )
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }) { Text("Report this wheel") }
            }
        }
    }
}
