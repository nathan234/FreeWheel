package org.freewheel.compose.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.freewheel.compose.WheelViewModel
import org.freewheel.compose.SmartBmsScreen
import org.freewheel.compose.screens.ChartScreen
import org.freewheel.compose.screens.DashboardEditScreen
import org.freewheel.compose.screens.DashboardScreen
import org.freewheel.compose.screens.MetricDetailScreen
import org.freewheel.compose.screens.NavigationEditScreen
import org.freewheel.compose.screens.RidesScreen
import org.freewheel.compose.screens.AutoConnectContent
import org.freewheel.compose.screens.ScanScreen
import org.freewheel.compose.screens.TripDetailScreen
import org.freewheel.compose.screens.WheelSettingsScreen
import org.freewheel.compose.screens.SettingsScreen
import org.freewheel.core.domain.dashboard.NavigationTab

/** Map [NavigationTab.iconName] to Material Icons. */
fun tabIcon(tab: NavigationTab): ImageVector = when (tab.iconName) {
    "bluetooth" -> Icons.Default.Bluetooth
    "show_chart" -> Icons.AutoMirrored.Filled.ShowChart
    "battery_full" -> Icons.Default.BatteryFull
    "route" -> Icons.Default.Route
    "tune" -> Icons.Default.Tune
    "settings" -> Icons.Default.Settings
    else -> Icons.Default.Bluetooth
}

// Routes that should hide the bottom bar (overlay screens)
private val overlayRoutes = setOf("edit_dashboard", "edit_navigation")

@Composable
fun AppNavigation(viewModel: WheelViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navigationConfig by viewModel.navigationConfig.collectAsStateWithLifecycle()
    val tabRoutes = navigationConfig.tabs.map { it.route }.toSet()

    // Hide bottom bar on overlay screens and metric/trip detail
    val showBottomBar = currentRoute !in overlayRoutes
        && currentRoute?.startsWith("metric/") != true
        && currentRoute?.startsWith("trip/") != true
        // Hide bottom bar on screens that are NOT tabs (accessed from dashboard buttons)
        && (currentRoute == null || currentRoute in tabRoutes
            || currentRoute == "devices") // devices is always a valid route

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    navigationConfig.tabs.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tabIcon(tab), contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavigationTab.DEVICES.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Devices tab — shows dashboard when connected, scan when not
            composable(NavigationTab.DEVICES.route) {
                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val isAutoConnecting by viewModel.isAutoConnecting.collectAsStateWithLifecycle()
                if (connectionState.isConnected) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToChart = { navController.navigate("chart") },
                        onNavigateToBms = { navController.navigate("bms") },
                        onNavigateToMetric = { metricId -> navController.navigate("metric/$metricId") },
                        onNavigateToWheelSettings = { navController.navigate("wheel_settings") },
                        onNavigateToEditDashboard = { navController.navigate("edit_dashboard") }
                    )
                } else if (isAutoConnecting) {
                    AutoConnectContent(onCancel = { viewModel.disconnect() })
                } else {
                    ScanScreen(viewModel = viewModel)
                }
            }

            // Chart — as tab or overlay
            composable(NavigationTab.CHART.route) {
                ChartScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // BMS — as tab or overlay
            composable(NavigationTab.BMS.route) {
                SmartBmsScreen(viewModel = viewModel)
            }

            // Rides tab
            composable(NavigationTab.RIDES.route) {
                RidesScreen(
                    viewModel = viewModel,
                    onNavigateToTripDetail = { fileName ->
                        navController.navigate("trip/$fileName")
                    }
                )
            }

            // Wheel Settings — as tab or overlay
            composable(NavigationTab.WHEEL_SETTINGS.route) {
                WheelSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // Settings tab
            composable(NavigationTab.SETTINGS.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateToEditNavigation = { navController.navigate("edit_navigation") }
                )
            }

            // Metric detail (overlay)
            composable("metric/{metricId}") { backStackEntry ->
                val metricId = backStackEntry.arguments?.getString("metricId") ?: "speed"
                MetricDetailScreen(
                    viewModel = viewModel,
                    metricId = metricId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Trip detail (overlay)
            composable("trip/{fileName}") { backStackEntry ->
                val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
                TripDetailScreen(
                    viewModel = viewModel,
                    fileName = fileName,
                    onBack = { navController.popBackStack() }
                )
            }

            // Dashboard edit (overlay)
            composable("edit_dashboard") {
                DashboardEditScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // Navigation edit (overlay)
            composable("edit_navigation") {
                NavigationEditScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
