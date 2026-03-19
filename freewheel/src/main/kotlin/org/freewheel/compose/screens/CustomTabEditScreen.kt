package org.freewheel.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.NavigationTab

/**
 * Edit screen for a custom tab's layout.
 * Thin wrapper around [LayoutEditorContent] with custom-tab-specific load/save.
 */
@Composable
fun CustomTabEditScreen(
    viewModel: WheelViewModel,
    tabId: String,
    onBack: () -> Unit
) {
    val customTabLayouts by viewModel.customTabLayouts.collectAsStateWithLifecycle()
    val navigationConfig by viewModel.navigationConfig.collectAsStateWithLifecycle()
    val identity by viewModel.identityState.collectAsStateWithLifecycle()

    val currentLayout = customTabLayouts[tabId] ?: DashboardLayout.default()
    val tabLabel = navigationConfig.tabs
        .filterIsInstance<NavigationTab.Custom>()
        .firstOrNull { it.id == tabId }
        ?.label ?: "Custom"

    LayoutEditorContent(
        title = "Edit $tabLabel",
        currentLayout = currentLayout,
        wheelType = identity.wheelType,
        onSave = { layout -> viewModel.saveCustomTabLayout(tabId, layout); onBack() },
        onCancel = onBack
    )
}
