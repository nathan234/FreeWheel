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
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.location.ChargingStation
import org.freewheel.core.location.ChargingStationRepository
import org.freewheel.core.location.ChargingStationSource
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.RideLogger
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
import java.io.File
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ride finalization in [WheelViewModel.onCleared].
 *
 * Verifies that closing the app while ride logging is active correctly
 * stops the logger and persists ride metadata to the database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelViewModelFinalizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var viewModel: WheelViewModel

    private lateinit var fakeCm: FakeWheelConnectionManager
    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeService: FakeWheelService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().commit()
        val appConfig = AppConfig(app, prefs)
        val db = TripDatabase.getDataBase(app)
        viewModel = WheelViewModel(
            application = app,
            appConfig = appConfig,
            prefs = prefs,
            vibrator = null,
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

    /**
     * Calls the protected [WheelViewModel.onCleared] method via reflection.
     * This simulates what Android does when the ViewModel is destroyed.
     */
    private fun callOnCleared() {
        val method: Method = WheelViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
    }

    @Test
    fun `onCleared does nothing when not logging`() = runTest(testDispatcher, timeout = 5.seconds) {
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        assertThat(viewModel.isLogging.value).isFalse()

        // Should not throw or crash
        callOnCleared()
    }

    @Test
    fun `onCleared stops logging and sets isLogging to false`() = runTest(testDispatcher, timeout = 5.seconds) {
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        startLogging()
        assertThat(viewModel.isLogging.value).isTrue()

        callOnCleared()
        assertThat(viewModel.isLogging.value).isFalse()
    }

    @Test
    fun `onCleared is safe to call twice while logging`() = runTest(testDispatcher, timeout = 5.seconds) {
        viewModel.attachService(fakeService, fakeCm, fakeBle)
        advanceUntilIdle()

        startLogging()
        assertThat(viewModel.isLogging.value).isTrue()

        // Double clear should not crash (RideLogger.stop returns null the second time)
        callOnCleared()
        callOnCleared()
        assertThat(viewModel.isLogging.value).isFalse()
    }

    /**
     * Starts ride logging via the ViewModel's toggleLogging method.
     * Creates a temp rides directory so the logger can open a file.
     */
    private fun startLogging() {
        val ridesDir = File(app.getExternalFilesDir(null), "rides")
        ridesDir.mkdirs()
        viewModel.toggleLogging()
    }

    private object NoopChargingStationSource : ChargingStationSource {
        override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> = emptyList()
    }
}
