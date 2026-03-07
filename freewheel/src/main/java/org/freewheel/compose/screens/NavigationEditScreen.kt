package org.freewheel.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.navigation.tabIcon
import org.freewheel.core.domain.dashboard.NavigationConfig
import org.freewheel.core.domain.dashboard.NavigationTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationEditScreen(
    viewModel: WheelViewModel,
    onBack: () -> Unit
) {
    val currentConfig by viewModel.navigationConfig.collectAsStateWithLifecycle()
    val activeTabs = remember(currentConfig) { mutableStateListOf(*currentConfig.tabs.toTypedArray()) }

    val config = NavigationConfig(tabs = activeTabs.toList())
    val isConfigValid = config.isValid()
    val warnings = config.warnings()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Customize Navigation") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isConfigValid) {
                                viewModel.saveNavigationConfig(config)
                            }
                            onBack()
                        },
                        enabled = isConfigValid
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Choose which screens appear as tabs. Devices is always included.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Tab list
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    NavigationTab.entries.forEachIndexed { index, tab ->
                        val isActive = tab in activeTabs
                        val activeIndex = activeTabs.indexOf(tab)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    tabIcon(tab),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    tab.label,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }

                            if (isActive) {
                                Row {
                                    IconButton(
                                        onClick = {
                                            if (activeIndex > 0) {
                                                val item = activeTabs.removeAt(activeIndex)
                                                activeTabs.add(activeIndex - 1, item)
                                            }
                                        },
                                        enabled = activeIndex > 0
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, "Move up")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (activeIndex < activeTabs.size - 1) {
                                                val item = activeTabs.removeAt(activeIndex)
                                                activeTabs.add(activeIndex + 1, item)
                                            }
                                        },
                                        enabled = activeIndex < activeTabs.size - 1
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, "Move down")
                                    }
                                }
                            }

                            Switch(
                                checked = isActive,
                                onCheckedChange = { checked ->
                                    if (tab.isRequired) return@Switch
                                    if (checked) {
                                        if (activeTabs.size < 5) activeTabs.add(tab)
                                    } else {
                                        if (activeTabs.size > 2) activeTabs.remove(tab)
                                    }
                                },
                                enabled = !tab.isRequired
                            )
                        }

                        if (index < NavigationTab.entries.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Warnings
            if (warnings.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        warnings.forEach { warning ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Live preview
            Text(
                "Preview",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            NavigationBar {
                activeTabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tabIcon(tab), contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = tab == NavigationTab.DEVICES,
                        onClick = {}
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
