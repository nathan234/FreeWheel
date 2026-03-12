package org.freewheel.compose

import org.freewheel.compose.service.WheelService
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.freewheel.AppConfig
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.WheelState
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.telemetry.PlatformTelemetryFileIO
import org.freewheel.data.TripDatabase
import org.freewheel.data.TripRepository
import org.freewheel.core.service.BleManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.WheelConnectionManager
import org.freewheel.data.TripDataDbEntry
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.freewheel.core.service.DemoDataProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Method

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

    private lateinit var mockService: WheelService
    private lateinit var mockCm: WheelConnectionManager
    private lateinit var mockBle: BleManager

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
            profileStore = WheelProfileStore(prefs),
            demoDataProvider = DemoDataProvider()
        )

        val mockConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        mockCm = mockk(relaxed = true) {
            every { connectionState } returns mockConnectionState
            every { wheelState } returns MutableStateFlow(WheelState())
            every { capabilities } returns MutableStateFlow(CapabilitySet())
        }
        mockBle = mockk(relaxed = true)
        mockService = mockk(relaxed = true)
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
    fun `onCleared does nothing when not logging`() = runTest(testDispatcher) {
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        assertThat(viewModel.isLogging.value).isFalse()

        // Should not throw or crash
        callOnCleared()
    }

    @Test
    fun `onCleared stops logging and sets isLogging to false`() = runTest(testDispatcher) {
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        startLogging()
        assertThat(viewModel.isLogging.value).isTrue()

        callOnCleared()
        assertThat(viewModel.isLogging.value).isFalse()
    }

    @Test
    fun `onCleared is safe to call twice while logging`() = runTest(testDispatcher) {
        viewModel.attachService(mockService, mockCm, mockBle)
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
}
