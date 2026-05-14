package org.freewheel.compose

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.freewheel.core.domain.PasswordManagementInput
import org.freewheel.core.domain.PasswordManagementState
import org.freewheel.core.domain.PasswordStorageBacking
import org.freewheel.core.domain.SharedPreferencesKeyValueStore
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.domain.WheelSettings
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
 * Behavioral coverage for [WheelViewModel]'s Veteran password-management
 * ack-listener wiring. The critical invariant tested here:
 *
 *   For SET / MODIFY / CLEAR (wire action 11), confirmation REQUIRES a
 *   state-changing lockState readback within the deadline. A snapshot
 *   whose bit 0 was already set from a *prior* successful command must
 *   NOT be treated as a fresh ack — otherwise the next op falsely lands
 *   in Confirmed and applies persistence the wheel never authorized.
 *
 *   For AUTO_LOCK_ON / AUTO_LOCK_OFF (wire action 2/3), bit 5 reflects
 *   the *current* auto-lock state, so a re-emitted same-value lockState
 *   matching the operation's intent IS a genuine confirmation.
 *
 * The same-state fallback policy lives in
 * WheelViewModel.startPasswordAckListener — these tests pin it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelViewModelPasswordManagementTest {

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

    private fun connectVeteranWith(lockState: Int) {
        fakeCm.setIdentity(WheelIdentity(wheelType = WheelType.VETERAN))
        fakeCm.setSettings(WheelSettings.Veteran(lockState = lockState))
        fakeCm.setConnectionState(ConnectionState.Connected(testMac, wheelName = "FakeWheel"))
    }

    @Test
    fun `MODIFY does not confirm when bit 0 already high and no fresh readback`() = runTest {
        // Baseline lockState = 0x41 (bits 0 + 6 set): a previous password
        // command succeeded AND the wheel has a password. This is the exact
        // shape that lets a stale bit-0 leak into a fresh op if the listener
        // trusts the snapshot.
        connectVeteranWith(lockState = 0x41)
        // Pre-warm the combined settingsState pipeline so .value reflects the
        // fake's settings — mirrors the production contract that the entry
        // card has already read at least one subtype-5 frame.
        val warmJob = launch { viewModel.settingsState.collect {} }
        advanceUntilIdle()
        // Sanity: baseline visible to the VM.
        assertThat((viewModel.settingsState.first() as WheelSettings.Veteran).lockState).isEqualTo(0x41)

        viewModel.requestPasswordManagement(PasswordManagementState.Operation.MODIFY)
        advanceUntilIdle()
        assertThat(viewModel.passwordManagementState.value)
            .isInstanceOf(PasswordManagementState.Editing::class.java)

        viewModel.submitPasswordManagement(
            PasswordManagementInput(
                oldPassword = "111111",
                newPassword = "222222",
                confirmationPassword = "222222",
                rememberNewPassword = true,
            ),
        )
        // The wheel never emits a fresh lockState frame. Advance through the
        // full 2s deadline budget.
        advanceUntilIdle()

        // Confirm the command actually dispatched (proves the listener was armed).
        assertThat(fakeCm.sentCommands.filterIsInstance<WheelCommand.ModifyVeteranPassword>())
            .isNotEmpty()

        // The critical assertion: terminal state is TIMEOUT, NOT Confirmed.
        val state = viewModel.passwordManagementState.value
        assertThat(state).isInstanceOf(PasswordManagementState.Failed::class.java)
        assertThat((state as PasswordManagementState.Failed).reason)
            .isEqualTo(PasswordManagementState.FailureReason.TIMEOUT)

        // Persistence must NOT have been applied — the wheel never confirmed.
        assertThat(passwordStore.getPassword(testMac)).isNull()

        warmJob.cancel()
    }

    @Test
    fun `AUTO_LOCK_ON confirms when bit 5 already set and no fresh readback`() = runTest {
        // Positive control: same wire shape (re-emit same lockState) but for
        // an auto-lock op, the same-state fallback IS valid because bit 5
        // reflects the current state rather than an ack of the last command.
        // Baseline 0x60 = bits 5 + 6 (auto-lock on, password set).
        connectVeteranWith(lockState = 0x60)
        val warmJob = launch { viewModel.settingsState.collect {} }
        advanceUntilIdle()

        viewModel.requestPasswordManagement(PasswordManagementState.Operation.AUTO_LOCK_ON)
        advanceUntilIdle()

        viewModel.submitPasswordManagement(
            PasswordManagementInput(
                oldPassword = "111111",
                newPassword = "",
                confirmationPassword = "",
                rememberNewPassword = false,
            ),
        )
        advanceUntilIdle()

        assertThat(fakeCm.sentCommands.filterIsInstance<WheelCommand.SetVeteranAutoLock>())
            .isNotEmpty()

        // Same-state confirmation is acceptable for auto-lock.
        assertThat(viewModel.passwordManagementState.value)
            .isInstanceOf(PasswordManagementState.Confirmed::class.java)

        warmJob.cancel()
    }

    private object NoopChargingStationSource : ChargingStationSource {
        override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> = emptyList()
    }
}
