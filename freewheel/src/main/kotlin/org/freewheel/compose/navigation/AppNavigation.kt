package org.freewheel.compose.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
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
import org.freewheel.compose.screens.ChartScreen
import org.freewheel.compose.screens.CustomTabEditScreen
import org.freewheel.compose.screens.CustomTabScreen
import org.freewheel.compose.screens.DashboardEditScreen
import org.freewheel.compose.screens.DashboardScreen
import org.freewheel.compose.screens.MetricDetailScreen
import org.freewheel.compose.screens.NavigationEditScreen
import org.freewheel.compose.screens.BleCaptureScreen
import org.freewheel.compose.screens.ConnectionErrorLogScreen
import org.freewheel.compose.screens.EventLogScreen
import org.freewheel.compose.screens.ChargerScreen
import org.freewheel.compose.screens.RidesScreen
import org.freewheel.compose.screens.AutoConnectContent
import org.freewheel.compose.screens.ScanScreen
import org.freewheel.compose.screens.SmartBmsScreen
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
    "dashboard" -> Icons.Default.Dashboard
    "speed" -> Icons.Default.Speed
    "star" -> Icons.Default.Star
    "favorite" -> Icons.Default.Favorite
    "bolt" -> Icons.Default.Bolt
    "visibility" -> Icons.Default.Visibility
    "thermostat" -> Icons.Default.Thermostat
    else -> Icons.Default.Dashboard
}

// Routes that should hide the bottom bar (overlay screens)
private val overlayRoutes = setOf(Routes.EDIT_DASHBOARD, Routes.EDIT_NAVIGATION, Routes.BLE_CAPTURE, Routes.EVENT_LOG, Routes.CONNECTION_ERROR_LOG)

@Composable
fun AppNavigation(viewModel: WheelViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navigationConfig by viewModel.navigationConfig.collectAsStateWithLifecycle()
    val tabRoutes = navigationConfig.tabs.map { it.route }.toSet()

    // Hide bottom bar on overlay screens and metric/trip detail
    val showBottomBar = currentRoute !in overlayRoutes
        && currentRoute?.startsWith(Routes.METRIC_PREFIX) != true
        && currentRoute?.startsWith(Routes.TRIP_PREFIX) != true
        && currentRoute?.startsWith(Routes.EDIT_CUSTOM_PREFIX) != true
        // Hide bottom bar on screens that are NOT tabs (accessed from dashboard buttons)
        && (currentRoute == null || currentRoute in tabRoutes
            || currentRoute == NavigationTab.Devices.route
            || currentRoute?.startsWith(Routes.CUSTOM_PREFIX) == true)

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
            startDestination = NavigationTab.Devices.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Devices tab — shows dashboard when connected, scan when not
            composable(NavigationTab.Devices.route) {
                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val isAutoConnecting by viewModel.isAutoConnecting.collectAsStateWithLifecycle()
                if (connectionState.isConnected) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToChart = { navController.navigate(NavigationTab.Chart.route) },
                        onNavigateToBms = { navController.navigate(NavigationTab.Bms.route) },
                        onNavigateToMetric = { metricId -> navController.navigate(Routes.metricDetail(metricId)) },
                        onNavigateToWheelSettings = { navController.navigate(NavigationTab.WheelSettings.route) },
                        onNavigateToEditDashboard = { navController.navigate(Routes.EDIT_DASHBOARD) }
                    )
                } else if (isAutoConnecting) {
                    AutoConnectContent(onCancel = { viewModel.disconnect() })
                } else {
                    ScanScreen(viewModel = viewModel)
                }
            }

            // Chart — as tab or overlay
            composable(NavigationTab.Chart.route) {
                ChartScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // BMS — as tab or overlay
            composable(NavigationTab.Bms.route) {
                SmartBmsScreen(viewModel = viewModel)
            }

            // Rides tab
            composable(NavigationTab.Rides.route) {
                RidesScreen(
                    viewModel = viewModel,
                    onNavigateToTripDetail = { fileName ->
                        navController.navigate(Routes.tripDetail(fileName))
                    }
                )
            }

            // Wheel Settings — as tab or overlay
            composable(NavigationTab.WheelSettings.route) {
                WheelSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // Charger tab
            composable(NavigationTab.Charger.route) {
                ChargerScreen(viewModel = viewModel)
            }

            // Settings tab
            composable(NavigationTab.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateToEditNavigation = { navController.navigate(Routes.EDIT_NAVIGATION) },
                    onNavigateToCapture = { navController.navigate(Routes.BLE_CAPTURE) },
                    onNavigateToEventLog = { navController.navigate(Routes.EVENT_LOG) },
                    onNavigateToErrorLog = { navController.navigate(Routes.CONNECTION_ERROR_LOG) }
                )
            }

            // Custom tab
            composable(Routes.CUSTOM_TAB) { backStackEntry ->
                val tabId = backStackEntry.arguments?.getString("tabId") ?: ""
                CustomTabScreen(
                    viewModel = viewModel,
                    tabId = tabId,
                    onNavigateToMetric = { metricId -> navController.navigate(Routes.metricDetail(metricId)) },
                    onNavigateToEditLayout = { navController.navigate(Routes.editCustomTab(tabId)) }
                )
            }

            // Metric detail (overlay)
            composable(Routes.METRIC_DETAIL) { backStackEntry ->
                val metricId = backStackEntry.arguments?.getString("metricId") ?: "speed"
                MetricDetailScreen(
                    viewModel = viewModel,
                    metricId = metricId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Trip detail (overlay)
            composable(Routes.TRIP_DETAIL) { backStackEntry ->
                val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
                TripDetailScreen(
                    viewModel = viewModel,
                    fileName = fileName,
                    onBack = { navController.popBackStack() }
                )
            }

            // Dashboard edit (overlay)
            composable(Routes.EDIT_DASHBOARD) {
                DashboardEditScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // Custom tab edit (overlay)
            composable(Routes.EDIT_CUSTOM_TAB) { backStackEntry ->
                val tabId = backStackEntry.arguments?.getString("tabId") ?: ""
                CustomTabEditScreen(
                    viewModel = viewModel,
                    tabId = tabId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Navigation edit (overlay)
            composable(Routes.EDIT_NAVIGATION) {
                NavigationEditScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToEditCustomTab = { tabId ->
                        navController.navigate(Routes.editCustomTab(tabId))
                    }
                )
            }

            // Event Log (overlay, Veteran/Leaperkim)
            composable(Routes.EVENT_LOG) {
                EventLogScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // BLE Capture (overlay)
            composable(Routes.BLE_CAPTURE) {
                BleCaptureScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onReplay = {
                        // Navigate to Devices tab (shows Dashboard when connected)
                        navController.navigate(NavigationTab.Devices.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    }
                )
            }

            // Connection Error Log (overlay)
            composable(Routes.CONNECTION_ERROR_LOG) {
                ConnectionErrorLogScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
