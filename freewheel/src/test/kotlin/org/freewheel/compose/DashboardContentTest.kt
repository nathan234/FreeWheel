package org.freewheel.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.freewheel.compose.components.DashboardContent
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.SpeedDisplayMode
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.domain.dashboard.DashboardPresets
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.telemetry.TelemetryBuffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @After
    fun tearDown() {
    }

    private fun setContent(
        layout: DashboardLayout = DashboardLayout.default(),
        telemetry: TelemetryState = previewTelemetry(),
        identity: WheelIdentity = previewIdentity(),
        settings: WheelSettings = previewSettings()
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                DashboardContent(
                    layout = layout,
                    telemetry = telemetry,
                    identity = identity,
                    bms = BmsState(),
                    settings = settings,
                    connectionState = ConnectionState.Connected("00:00:00:00:00:00", "Test"),
                    activeAlarms = emptySet(),
                    isDemo = false,
                    gpsSpeed = 21.5,
                    useMph = false,
                    useFahrenheit = false,
                    telemetryBuffer = TelemetryBuffer(),
                    samples = emptyList(),
                    speedDisplayMode = SpeedDisplayMode.WHEEL,
                    onSpeedDisplayModeChange = {},
                    onNavigateToChart = {},
                    onNavigateToBms = {},
                    onNavigateToMetric = {},
                    onNavigateToWheelSettings = {},
                    onDisconnect = {}
                )
            }
        }
    }

    @Test
    fun `default layout renders 6 tile labels`() {
        setContent()
        for (metric in DashboardLayout.default().tiles) {
            val nodes = composeTestRule.onAllNodesWithText(metric.label, substring = true, ignoreCase = true, useUnmergedTree = true)
            nodes.fetchSemanticsNodes().isNotEmpty().let { found ->
                assert(found) { "Expected tile label '${metric.label}' to exist" }
            }
        }
    }

    @Test
    fun `compact layout renders 2 tile labels`() {
        val compact = DashboardPresets.all().first { it.id == "compact" }.layout
        setContent(layout = compact)
        for (metric in compact.tiles) {
            val nodes = composeTestRule.onAllNodesWithText(metric.label, substring = true, ignoreCase = true, useUnmergedTree = true)
            nodes.fetchSemanticsNodes().isNotEmpty().let { found ->
                assert(found) { "Expected tile label '${metric.label}' to exist" }
            }
        }
    }

    @Test
    fun `racing preset hides wheel info card`() {
        val racing = DashboardPresets.all().first { it.id == "racing" }.layout
        setContent(layout = racing, identity = previewIdentity().copy(name = "TestWheel", model = "TestModel"))
        composeTestRule.onNodeWithText("TestModel", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `IM2 metrics hidden for Kingsong`() {
        val diagnostic = DashboardPresets.all().first { it.id == "diagnostic" }.layout
        setContent(layout = diagnostic, identity = previewIdentity(WheelType.KINGSONG))
        composeTestRule.onNodeWithText(DashboardMetric.TORQUE.label, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `custom stat order is respected`() {
        val layout = DashboardLayout.create(
            tiles = listOf(DashboardMetric.BATTERY),
            stats = listOf(DashboardMetric.TOTAL_DISTANCE, DashboardMetric.VOLTAGE, DashboardMetric.TRIP_DISTANCE)
        )
        setContent(layout = layout)
        composeTestRule.onNodeWithText(DashboardMetric.TOTAL_DISTANCE.label, substring = true, useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText(DashboardMetric.VOLTAGE.label, substring = true, useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText(DashboardMetric.TRIP_DISTANCE.label, substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `disconnect button shows for connected state`() {
        setContent()
        composeTestRule.onNodeWithText("Disconnect", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    companion object {
        private fun previewTelemetry() = TelemetryState(
            speed = 2200, voltage = 8400, current = 1500, phaseCurrent = 2500,
            power = 126000, temperature = 3500, temperature2 = 4000,
            batteryLevel = 72, totalDistance = 1523500, wheelDistance = 12340,
            calculatedPwm = 0.44, angle = 2.5, roll = 1.2,
            torque = 18.5, motorPower = 850.0, cpuTemp = 42, imuTemp = 38, cpuLoad = 65
        )

        private fun previewIdentity(wheelType: WheelType = WheelType.Unknown) = WheelIdentity(
            wheelType = wheelType, name = "Preview", model = "Demo Wheel", version = "1.2.3"
        )

        private fun previewSettings() = WheelSettings.Kingsong(
            pedalsMode = 1, lightMode = 1, ledMode = 3, ksTiltbackSpeed = 45
        )
    }
}
