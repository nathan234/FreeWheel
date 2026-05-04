package org.freewheel.compose

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.freewheel.AppConfig
import org.freewheel.core.domain.AppSettingsStore
import org.freewheel.core.domain.ChargerProfileStore
import org.freewheel.core.domain.SharedPreferencesKeyValueStore
import org.freewheel.core.domain.WheelProfile
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.location.ChargingStation
import org.freewheel.core.location.ChargingStationRepository
import org.freewheel.core.location.ChargingStationSource
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.service.AutoConnectManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.DemoDataProvider
import org.freewheel.core.telemetry.PlatformTelemetryFileIO
import org.freewheel.data.TripDatabase
import org.freewheel.data.TripRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests verifying WheelViewModel wires correctly to the shared
 * AutoConnectManager. Uses fake implementations instead of mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelViewModelAutoConnectTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var viewModel: WheelViewModel
    private lateinit var appConfig: AppConfig
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var fakeCm: FakeWheelConnectionManager
    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeService: FakeWheelService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        app = ApplicationProvider.getApplicationContext()

        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().commit()
        appConfig = AppConfig(app, prefs)

        val db = TripDatabase.getDataBase(app)
        viewModel = WheelViewModel(
            app, appConfig, prefs, null,
            tripRepository = TripRepository(db.tripDao()),
            rideLogger = RideLogger(),
            captureLogger = BleCaptureLogger(),
            telemetryFileIO = PlatformTelemetryFileIO(),
            profileStore = WheelProfileStore(SharedPreferencesKeyValueStore(prefs)),
            chargerProfileStore = ChargerProfileStore(SharedPreferencesKeyValueStore(prefs)),
            appSettingsStore = AppSettingsStore(SharedPreferencesKeyValueStore(prefs)),
            demoDataProvider = DemoDataProvider(),
            chargingStationRepository = ChargingStationRepository(NoopChargingStationSource)
        )

        fakeCm = FakeWheelConnectionManager()
        fakeBle = FakeBleManager()
        val fakeChargerCm = FakeChargerConnectionManager()
        fakeService = FakeWheelService(fakeChargerCm, fakeBle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setLastMac(mac: String) {
        PreferenceManager.getDefaultSharedPreferences(app)
            .edit()
            .putString("last_mac", mac)
            .commit()
    }

    private fun getLastMac(): String {
        return PreferenceManager.getDefaultSharedPreferences(app)
            .getString("last_mac", "") ?: ""
    }

    private fun setUseReconnect(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(app)
            .edit()
            .putBoolean("use_reconnect", enabled)
            .commit()
    }

    // --- ViewModel guards (these stay as integration tests) ---

    @Test
    fun `attemptStartupAutoConnect does nothing when lastMac is empty`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(true)
        setLastMac("")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        advanceUntilIdle()

        assertThat(viewModel.isAutoConnecting.value).isFalse()
        assertThat(fakeCm.connectCallCount).isEqualTo(0)
    }

    @Test
    fun `attemptStartupAutoConnect does nothing when useReconnect is false`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(false)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        advanceUntilIdle()

        assertThat(viewModel.isAutoConnecting.value).isFalse()
        assertThat(fakeCm.connectCallCount).isEqualTo(0)
    }

    @Test
    fun `attemptStartupAutoConnect does nothing when no service attached`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        // Don't call attachService

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isFalse()
    }

    // --- Wiring verification: delegates to shared manager ---

    @Test
    fun `attemptStartupAutoConnect delegates to shared manager`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isTrue()

        // AutoConnectManager dispatches connect on Dispatchers.Default (real thread).
        // Give it a moment to execute, then verify the fake recorded the call.
        Thread.sleep(50)
        assertThat(fakeCm.lastConnectAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `disconnect stops auto-connect and clears lastMac`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isTrue()

        viewModel.disconnect()
        advanceUntilIdle()

        assertThat(viewModel.isAutoConnecting.value).isFalse()
        assertThat(getLastMac()).isEmpty()
    }

    @Test
    fun `connect persists MAC address`() = runTest(testDispatcher, timeout = 5.seconds) {
        setLastMac("")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.connect("11:22:33:44:55:66")
        advanceUntilIdle()

        assertThat(getLastMac()).isEqualTo("11:22:33:44:55:66")
    }

    // --- Reconnect-after-loss wiring ---
    // OS-level auto-reconnect (Android: autoConnectPeripheral, iOS: centralManager.connect)
    // now handles mid-ride reconnection. The ViewModel no longer starts AutoConnectManager
    // on ConnectionLost — that would cancel the OS reconnect.

    @Test
    fun `ConnectionLost does not trigger app-level reconnect`() = runTest(testDispatcher, timeout = 5.seconds) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        // Simulate connection lost — should NOT start AutoConnectManager reconnect
        fakeCm.setConnectionState(ConnectionState.ConnectionLost("AA:BB:CC:DD:EE:FF", "timeout"))
        advanceUntilIdle()

        assertThat(viewModel.reconnectState.value).isEqualTo(AutoConnectManager.ReconnectState.Idle)
    }

    // --- Startup scan (scan-then-auto-connect) ---

    @Test
    fun `startupScan does nothing when lastMac is empty`() = runTest(testDispatcher, timeout = 5.seconds) {
        setLastMac("")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.startupScan()
        advanceUntilIdle()

        assertThat(viewModel.isScanning.value).isFalse()
        assertThat(fakeBle.startScanCallCount).isEqualTo(0)
    }

    @Test
    fun `startupScan starts scanning when lastMac is set`() = runTest(testDispatcher, timeout = 5.seconds) {
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.startupScan()
        advanceUntilIdle()

        assertThat(viewModel.isScanning.value).isTrue()
        assertThat(fakeBle.startScanCallCount).isEqualTo(1)
    }

    // --- Wheel profile persistence ---

    @Test
    fun `auto-save profile on connection`() = runTest(testDispatcher, timeout = 5.seconds) {
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        // Simulate connection via the fake CM's flow
        fakeCm.setConnectionState(ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test Wheel"))
        advanceUntilIdle()

        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `forgetProfile removes address from saved set`() = runTest(testDispatcher, timeout = 5.seconds) {
        // Pre-save a profile
        viewModel.profileStore.saveProfile(
            WheelProfile("AA:BB:CC:DD:EE:FF", "My Wheel", "KINGSONG", 1000L)
        )
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")

        viewModel.forgetProfile("AA:BB:CC:DD:EE:FF")
        assertThat(viewModel.savedAddresses.value).doesNotContain("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `disconnect does not remove saved profile`() = runTest(testDispatcher, timeout = 5.seconds) {
        // Pre-save a profile
        viewModel.profileStore.saveProfile(
            WheelProfile("AA:BB:CC:DD:EE:FF", "My Wheel", "KINGSONG", 1000L)
        )
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        // Profile should still be saved
        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")
    }

    private object NoopChargingStationSource : ChargingStationSource {
        override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> = emptyList()
    }
}
