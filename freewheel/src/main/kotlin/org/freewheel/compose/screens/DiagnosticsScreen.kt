package org.freewheel.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import org.freewheel.BuildConfig
import org.freewheel.core.diagnostics.Diagnostics

private const val PAGE_SIZE = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rawLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var levelFilter by remember { mutableStateOf<Set<String>>(setOf("error", "warn", "info")) }
    var categoryFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    fun reload() {
        rawLines = Diagnostics.readRecent(PAGE_SIZE).reversed()
    }

    LaunchedEffect(Unit) { reload() }

    val parsed by remember {
        derivedStateOf { rawLines.map { parseLineForDisplay(it) } }
    }
    val filtered by remember {
        derivedStateOf {
            parsed.filter {
                it.level in levelFilter &&
                    (categoryFilter.isEmpty() || it.category in categoryFilter)
            }
        }
    }
    val visibleCategories = remember(parsed) {
        parsed.map { it.category }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::reload) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { sendToDeveloper(context) }) {
                        Icon(Icons.Default.Email, contentDescription = "Send to developer")
                    }
                    IconButton(onClick = { share(context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { Diagnostics.clear(); reload() }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("error", "warn", "info").forEach { lvl ->
                    FilterChip(
                        selected = lvl in levelFilter,
                        onClick = {
                            levelFilter = if (lvl in levelFilter) levelFilter - lvl else levelFilter + lvl
                        },
                        label = { Text(lvl.uppercase()) }
                    )
                }
            }
            if (visibleCategories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleCategories.sorted().forEach { cat ->
                        FilterChip(
                            selected = cat in categoryFilter,
                            onClick = {
                                categoryFilter = if (cat in categoryFilter) categoryFilter - cat else categoryFilter + cat
                            },
                            label = { Text(cat) }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No matching events.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(filtered, key = { i, _ -> i }) { i, evt ->
                        EventRow(
                            event = evt,
                            expanded = expandedIndex == i,
                            onToggle = { expandedIndex = if (expandedIndex == i) null else i }
                        )
                    }
                }
            }
        }
    }
}

private data class DisplayEvent(
    val raw: String,
    val timestamp: String,
    val level: String,
    val category: String,
    val type: String,
    val message: String,
)

private fun parseLineForDisplay(raw: String): DisplayEvent {
    fun field(key: String): String =
        Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*?)\"")
            .find(raw)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", " ")
            ?: ""
    return DisplayEvent(
        raw = raw,
        timestamp = field("ts"),
        level = field("level"),
        category = field("category"),
        type = field("type"),
        message = field("message"),
    )
}

@Composable
private fun EventRow(event: DisplayEvent, expanded: Boolean, onToggle: () -> Unit) {
    val (dot, container) = when (event.level) {
        "error" -> Color(0xFFE53935) to MaterialTheme.colorScheme.errorContainer
        "warn" -> Color(0xFFFFA726) to MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color(0xFF9E9E9E) to MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dot, CircleShape)
                    .padding(top = 6.dp)
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${event.timestamp.substringAfter('T').substringBefore('Z').take(8)}  ${event.type}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    event.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (expanded) {
                    Text(
                        event.raw,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun share(context: Context) {
    val bundle = buildBundleFile(context) ?: return
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", bundle
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
}

private fun sendToDeveloper(context: Context) {
    val bundle = buildBundleFile(context) ?: return
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", bundle
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
        putExtra(
            Intent.EXTRA_SUBJECT,
            "[FW-Diag] FreeWheel Diagnostics — v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        )
        putExtra(Intent.EXTRA_TEXT, BODY_TEMPLATE)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Send to developer"))
}

private const val BODY_TEMPLATE = """Hi! Something happened with my ride logging — describe below:




Ride affected (filename or approximate time, optional):


---
DO NOT EDIT BELOW THIS LINE
---
The attached diagnostics-*.txt file has the event log and a current-state snapshot.
"""

private fun buildBundleFile(context: Context): File? {
    val active = Diagnostics.activeFilePath() ?: return null
    val activeFile = File(active)
    val outDir = File(context.cacheDir, "diagnostics-bundles")
    outDir.mkdirs()
    val ts = System.currentTimeMillis()
    val out = File(outDir, "diagnostics-$ts.txt")
    val header = buildString {
        appendLine("=== FreeWheel Diagnostics ===")
        appendLine("App version:    ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("Platform:       Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Locale:         ${java.util.Locale.getDefault()}")
        appendLine("Generated:      ${java.time.Instant.ofEpochMilli(ts)}")
        appendLine()
        appendLine("=== Event log (most recent first) ===")
    }
    val recent = Diagnostics.readRecent(2000).reversed()
    out.writeText(header + recent.joinToString("\n"))
    return out
}
