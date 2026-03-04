package org.freewheel.compose.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
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
import org.freewheel.core.domain.RidesLabels
import org.freewheel.core.domain.ScanLabels
import org.freewheel.core.domain.SettingsLabels
import org.freewheel.compose.SmartBmsScreen
import org.freewheel.compose.screens.ChartScreen
import org.freewheel.compose.screens.DashboardScreen
import org.freewheel.compose.screens.MetricDetailScreen
import org.freewheel.compose.screens.RidesScreen
import org.freewheel.compose.screens.AutoConnectContent
import org.freewheel.compose.screens.ScanScreen
import org.freewheel.compose.screens.TripDetailScreen
import org.freewheel.compose.screens.WheelSettingsScreen
import org.freewheel.compose.screens.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Devices : Screen("devices", ScanLabels.TITLE, Icons.Default.Bluetooth)
    data object Rides : Screen("rides", RidesLabels.TITLE, Icons.Default.Route)
    data object Settings : Screen("settings", SettingsLabels.TITLE, Icons.Default.Settings)
}

private val bottomTabs = listOf(Screen.Devices, Screen.Rides, Screen.Settings)

@Composable
fun AppNavigation(viewModel: WheelViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide bottom bar on chart, BMS, metric detail, and wheel settings screens
    val showBottomBar = currentRoute != "chart" && currentRoute != "bms"
        && currentRoute != "wheel_settings"
        && currentRoute?.startsWith("metric/") != true
        && currentRoute?.startsWith("trip/") != true

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
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
            startDestination = Screen.Devices.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Devices.route) {
                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val isAutoConnecting by viewModel.isAutoConnecting.collectAsStateWithLifecycle()
                if (connectionState.isConnected) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToChart = { navController.navigate("chart") },
                        onNavigateToBms = { navController.navigate("bms") },
                        onNavigateToMetric = { metricId -> navController.navigate("metric/$metricId") },
                        onNavigateToWheelSettings = { navController.navigate("wheel_settings") }
                    )
                } else if (isAutoConnecting) {
                    AutoConnectContent(onCancel = { viewModel.disconnect() })
                } else {
                    ScanScreen(viewModel = viewModel)
                }
            }
            composable(Screen.Rides.route) {
                RidesScreen(
                    viewModel = viewModel,
                    onNavigateToTripDetail = { fileName ->
                        navController.navigate("trip/$fileName")
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable("chart") {
                ChartScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("bms") {
                SmartBmsScreen(viewModel = viewModel)
            }
            composable("metric/{metricId}") { backStackEntry ->
                val metricId = backStackEntry.arguments?.getString("metricId") ?: "speed"
                MetricDetailScreen(
                    viewModel = viewModel,
                    metricId = metricId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("wheel_settings") {
                WheelSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("trip/{fileName}") { backStackEntry ->
                val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
                TripDetailScreen(
                    viewModel = viewModel,
                    fileName = fileName,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
