package org.freewheel.compose

import org.freewheel.compose.service.WheelService
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.freewheel.AppConfig
import org.freewheel.compose.di.AppModule
import org.freewheel.core.domain.WheelProfile
import org.freewheel.core.domain.WheelState
import org.freewheel.core.service.AutoConnectManager
import org.freewheel.core.service.BleManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.WheelConnectionManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests verifying WheelViewModel wires correctly to the shared
 * AutoConnectManager. Detailed state machine tests are in
 * core/commonTest/AutoConnectManagerTest.kt.
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

    private lateinit var mockService: WheelService
    private lateinit var mockCm: WheelConnectionManager
    private lateinit var mockBle: BleManager
    private lateinit var mockConnectionState: MutableStateFlow<ConnectionState>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        app = ApplicationProvider.getApplicationContext()

        // Create fresh instances per test (not lazy singletons) since
        // Robolectric creates a new Application per test method.
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().commit()
        appConfig = AppConfig(app)

        viewModel = WheelViewModel(app, appConfig, prefs, null)

        mockConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        mockCm = mockk(relaxed = true) {
            every { connectionState } returns mockConnectionState
            every { wheelState } returns MutableStateFlow(WheelState())
        }
        mockBle = mockk(relaxed = true)
        mockService = mockk(relaxed = true)
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
    fun `attemptStartupAutoConnect does nothing when lastMac is empty`() = runTest(testDispatcher) {
        setUseReconnect(true)
        setLastMac("")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isFalse()
        coVerify(exactly = 0) { mockCm.connect(any(), any()) }
    }

    @Test
    fun `attemptStartupAutoConnect does nothing when useReconnect is false`() = runTest(testDispatcher) {
        setUseReconnect(false)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isFalse()
        coVerify(exactly = 0) { mockCm.connect(any(), any()) }
    }

    @Test
    fun `attemptStartupAutoConnect does nothing when no service attached`() = runTest(testDispatcher) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        // Don't call attachService

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isFalse()
    }

    // --- Wiring verification: delegates to shared manager ---

    @Test
    fun `attemptStartupAutoConnect delegates to shared manager`() = runTest(testDispatcher) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isTrue()

        advanceTimeBy(100)
        coVerify { mockCm.connect("AA:BB:CC:DD:EE:FF", null) }
    }

    @Test
    fun `disconnect stops auto-connect and clears lastMac`() = runTest(testDispatcher) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.attemptStartupAutoConnect()
        assertThat(viewModel.isAutoConnecting.value).isTrue()

        viewModel.disconnect()
        advanceUntilIdle()

        assertThat(viewModel.isAutoConnecting.value).isFalse()
        assertThat(getLastMac()).isEmpty()
    }

    @Test
    fun `connect persists MAC address`() = runTest(testDispatcher) {
        setLastMac("")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.connect("11:22:33:44:55:66")
        advanceUntilIdle()

        assertThat(getLastMac()).isEqualTo("11:22:33:44:55:66")
    }

    // --- Reconnect-after-loss wiring ---

    @Test
    fun `ConnectionLost triggers reconnect when autoReconnect enabled`() = runTest(testDispatcher) {
        setUseReconnect(true)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        // Simulate connection lost
        mockConnectionState.value = ConnectionState.ConnectionLost("AA:BB:CC:DD:EE:FF", "timeout")
        advanceUntilIdle()

        assertThat(viewModel.reconnectState.value).isNotEqualTo(AutoConnectManager.ReconnectState.Idle)
    }

    @Test
    fun `ConnectionLost does not trigger reconnect when autoReconnect disabled`() = runTest(testDispatcher) {
        setUseReconnect(false)
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        mockConnectionState.value = ConnectionState.ConnectionLost("AA:BB:CC:DD:EE:FF", "timeout")
        advanceUntilIdle()

        assertThat(viewModel.reconnectState.value).isEqualTo(AutoConnectManager.ReconnectState.Idle)
    }

    // --- Startup scan (scan-then-auto-connect) ---

    @Test
    fun `startupScan does nothing when lastMac is empty`() = runTest(testDispatcher) {
        setLastMac("")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.startupScan()
        advanceUntilIdle()

        assertThat(viewModel.isScanning.value).isFalse()
        coVerify(exactly = 0) { mockBle.startScan(any()) }
    }

    @Test
    fun `startupScan starts scanning when lastMac is set`() = runTest(testDispatcher) {
        setLastMac("AA:BB:CC:DD:EE:FF")
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.startupScan()
        advanceUntilIdle()

        assertThat(viewModel.isScanning.value).isTrue()
        coVerify { mockBle.startScan(any()) }
    }

    // --- Wheel profile persistence ---

    @Test
    fun `auto-save profile on connection`() = runTest(testDispatcher) {
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        // Simulate connection
        mockConnectionState.value = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test Wheel")
        advanceUntilIdle()

        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `forgetProfile removes address from saved set`() = runTest(testDispatcher) {
        // Pre-save a profile
        viewModel.profileStore.saveProfile(
            WheelProfile("AA:BB:CC:DD:EE:FF", "My Wheel", "KINGSONG", 1000L)
        )
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")

        viewModel.forgetProfile("AA:BB:CC:DD:EE:FF")
        assertThat(viewModel.savedAddresses.value).doesNotContain("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `disconnect does not remove saved profile`() = runTest(testDispatcher) {
        // Pre-save a profile
        viewModel.profileStore.saveProfile(
            WheelProfile("AA:BB:CC:DD:EE:FF", "My Wheel", "KINGSONG", 1000L)
        )
        viewModel.attachService(mockService, mockCm, mockBle)
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        // Profile should still be saved
        assertThat(viewModel.savedAddresses.value).contains("AA:BB:CC:DD:EE:FF")
    }
}
