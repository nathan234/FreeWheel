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
import org.freewheel.compose.di.AppModule
import org.freewheel.core.domain.AppSettingsStore
import org.freewheel.core.domain.ChargerProfileStore
import org.freewheel.core.domain.DecoderConfigStore
import org.freewheel.core.domain.InMemoryWheelPasswordStore
import org.freewheel.core.domain.LockPromptState
import org.freewheel.core.domain.PasswordStorageBacking
import org.freewheel.core.domain.SharedPreferencesKeyValueStore
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.domain.WheelType
import org.freewheel.core.location.ChargingStation
import org.freewheel.core.location.ChargingStationRepository
import org.freewheel.core.location.ChargingStationSource
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.protocol.WheelCommand
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

/**
 * Behavioral coverage for [WheelViewModel]'s Veteran lock prompt wiring:
 * the LOCK toggle interception on Veteran wheels, address/action propagation
 * into [LockPromptState.AwaitingPassword], submit dispatch into
 * [WheelCommand.SetVeteranLock], password persistence behavior under the
 * "remember password" toggle, and the disconnected-at-submit error path.
 *
 * State-machine internals (validation rules, transitions) are covered by
 * the shared LockPromptStateTest; this file exercises the ViewModel glue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelViewModelLockPromptTest {

    private val testMac = "AA:BB:CC:DD:EE:01"

    private lateinit var app: Application
    private lateinit var viewModel: WheelViewModel
    private lateinit var fakeCm: FakeWheelConnectionManager
    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeService: FakeWheelService
    private lateinit var passwordStore: InMemoryWheelPasswordStore

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())

        app = ApplicationProvider.getApplicationContext()
        AppModule.initialize(app)
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().commit()
        val kvs = SharedPreferencesKeyValueStore(prefs)

        passwordStore = InMemoryWheelPasswordStore()

        val db = TripDatabase.getDataBase(app)
        viewModel = WheelViewModel(
            application = app,
            prefs = prefs,
            vibrator = null,
            tripRepository = TripRepository(db.tripDao()),
            rideLogger = RideLogger(),
            captureLogger = BleCaptureLogger(),
            telemetryFileIO = PlatformTelemetryFileIO(),
            profileStore = WheelProfileStore(kvs),
            chargerProfileStore = ChargerProfileStore(kvs),
            appSettingsStore = AppSettingsStore(kvs),
            decoderConfigStore = DecoderConfigStore(kvs),
            demoDataProvider = DemoDataProvider(),
            chargingStationRepository = ChargingStationRepository(NoopChargingStationSource),
            passwordStore = passwordStore,
            passwordStoreBacking = PasswordStorageBacking.SECURE,
        )

        fakeCm = FakeWheelConnectionManager()
        fakeBle = FakeBleManager()
        val fakeChargerCm = FakeChargerConnectionManager()
        fakeService = FakeWheelService(fakeChargerCm, fakeBle)
        viewModel.attachService(fakeService, fakeCm, fakeBle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun connectVeteran() {
        fakeCm.setIdentity(WheelIdentity(wheelType = WheelType.VETERAN))
        fakeCm.setConnectionState(ConnectionState.Connected(testMac, wheelName = "FakeWheel"))
    }

    @Test
    fun `requestLock opens AwaitingPassword for connected wheel with stored prefill`() = runTest {
        connectVeteran()
        passwordStore.setPassword(testMac, "555555")
        advanceUntilIdle()

        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        val state = viewModel.lockPromptState.value as LockPromptState.AwaitingPassword
        assertThat(state.action).isEqualTo(LockPromptState.LockAction.LOCK)
        assertThat(state.address).isEqualTo(testMac)
        assertThat(state.prefilledPassword).isEqualTo("555555")
        assertThat(state.canPersistPassword).isTrue()
    }

    @Test
    fun `requestLock is a no-op when disconnected`() = runTest {
        // Connection state stays Disconnected.
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        assertThat(viewModel.lockPromptState.value).isEqualTo(LockPromptState.Idle)
    }

    @Test
    fun `executeWheelCommand for LOCK on Veteran routes to lock prompt`() = runTest {
        connectVeteran()
        advanceUntilIdle()

        viewModel.executeWheelCommand(
            commandId = org.freewheel.core.domain.SettingsCommandId.LOCK,
            boolValue = true,
        )
        advanceUntilIdle()

        // Generic SetLock must NOT have been dispatched.
        assertThat(fakeCm.sentCommands).doesNotContain(WheelCommand.SetLock(true))
        // Prompt opened in LOCK direction.
        assertThat(viewModel.lockPromptState.value)
            .isInstanceOf(LockPromptState.AwaitingPassword::class.java)
        assertThat((viewModel.lockPromptState.value as LockPromptState.AwaitingPassword).action)
            .isEqualTo(LockPromptState.LockAction.LOCK)
    }

    @Test
    fun `submitLockPassword dispatches SetVeteranLock and transitions to Sent`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.UNLOCK)
        advanceUntilIdle()

        viewModel.submitLockPassword("123456", rememberPassword = false)
        advanceUntilIdle()

        // Wheel command dispatched with the right shape.
        val sent = fakeCm.sentCommands.last() as WheelCommand.SetVeteranLock
        assertThat(sent.locked).isFalse() // UNLOCK
        assertThat(sent.password).isEqualTo("123456")
        // State machine landed on Sent.
        assertThat(viewModel.lockPromptState.value).isEqualTo(LockPromptState.Sent)
    }

    @Test
    fun `submitLockPassword with rememberPassword=true persists`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        viewModel.submitLockPassword("999999", rememberPassword = true)
        advanceUntilIdle()

        assertThat(passwordStore.getPassword(testMac)).isEqualTo("999999")
    }

    @Test
    fun `submitLockPassword with rememberPassword=false does not persist`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        viewModel.submitLockPassword("999999", rememberPassword = false)
        advanceUntilIdle()

        assertThat(passwordStore.getPassword(testMac)).isNull()
    }

    @Test
    fun `submitLockPassword with rememberPassword=false clears a previously-saved password`() = runTest {
        // The dialog defaults the "remember" toggle to ON when a saved password
        // exists. If the user then explicitly turns it OFF and submits, the
        // stored entry must be wiped — otherwise we'd silently keep the
        // password against the user's opt-out.
        connectVeteran()
        passwordStore.setPassword(testMac, "555555")
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()
        // Sanity check: the prompt prefilled from the store.
        assertThat((viewModel.lockPromptState.value as LockPromptState.AwaitingPassword).prefilledPassword)
            .isEqualTo("555555")

        viewModel.submitLockPassword("555555", rememberPassword = false)
        advanceUntilIdle()

        assertThat(passwordStore.getPassword(testMac)).isNull()
    }

    @Test
    fun `submit with empty password emits Error EMPTY_PASSWORD without dispatching`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        viewModel.submitLockPassword("   ", rememberPassword = false)
        advanceUntilIdle()

        val state = viewModel.lockPromptState.value as LockPromptState.Error
        assertThat(state.reason).isEqualTo(LockPromptState.ErrorReason.EMPTY_PASSWORD)
        // No command dispatched.
        assertThat(fakeCm.sentCommands.filterIsInstance<WheelCommand.SetVeteranLock>()).isEmpty()
    }

    @Test
    fun `submit when disconnected mid-flow emits NOT_CONNECTED`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        // BLE drops between prompt open and submit.
        fakeCm.setConnectionState(ConnectionState.Disconnected)
        advanceUntilIdle()

        viewModel.submitLockPassword("123456", rememberPassword = false)
        advanceUntilIdle()

        val state = viewModel.lockPromptState.value as LockPromptState.Error
        assertThat(state.reason).isEqualTo(LockPromptState.ErrorReason.NOT_CONNECTED)
        assertThat(fakeCm.sentCommands.filterIsInstance<WheelCommand.SetVeteranLock>()).isEmpty()
    }

    @Test
    fun `dismissLockPrompt resets to Idle`() = runTest {
        connectVeteran()
        advanceUntilIdle()
        viewModel.requestLock(LockPromptState.LockAction.LOCK)
        advanceUntilIdle()

        viewModel.dismissLockPrompt()
        advanceUntilIdle()

        assertThat(viewModel.lockPromptState.value).isEqualTo(LockPromptState.Idle)
    }

    private object NoopChargingStationSource : ChargingStationSource {
        override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> = emptyList()
    }
}
