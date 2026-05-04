package org.freewheel.compose.di

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import android.os.Vibrator
import androidx.core.content.ContextCompat.getSystemService
import androidx.preference.PreferenceManager
import org.freewheel.AppConfig
import org.freewheel.core.domain.AppSettingsStore
import org.freewheel.core.domain.ChargerProfileStore
import org.freewheel.core.domain.SharedPreferencesKeyValueStore
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.alarm.AlarmChecker
import org.freewheel.core.location.ChargingStationRepository
import org.freewheel.core.location.OpenChargeMapClient
import org.freewheel.core.location.OpenChargeMapSource
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.service.DemoDataProvider
import org.freewheel.core.telemetry.PlatformTelemetryFileIO
import org.freewheel.BuildConfig
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetryFileIO
import org.freewheel.data.TripDatabase
import org.freewheel.data.TripRepository

object AppModule {
    private lateinit var appContext: Context

    fun initialize(appContext: Context) {
        this.appContext = appContext.applicationContext
    }
    val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }
    val appConfig: AppConfig by lazy {
        AppConfig(appContext, prefs)
    }
    val locationManager: LocationManager? by lazy {
        getSystemService(
            appContext,
            LocationManager::class.java
        )
    }
    val notificationManager: NotificationManager? by lazy {
        getSystemService(
            appContext,
            NotificationManager::class.java
        )
    }
    val bluetoothManager: BluetoothManager? by lazy {
        getSystemService(
            appContext,
            BluetoothManager::class.java
        )
    }
    val vibrator: Vibrator? by lazy {
        getSystemService(
            appContext,
            Vibrator::class.java
        )
    }
    val tripRepository: TripRepository by lazy {
        TripRepository(TripDatabase.getDataBase(appContext).tripDao())
    }
    val rideLogger: RideLogger by lazy { RideLogger() }
    val bleCaptureLogger: BleCaptureLogger by lazy { BleCaptureLogger() }
    val telemetryFileIO: TelemetryFileIO by lazy { PlatformTelemetryFileIO() }
    private val keyValueStore: SharedPreferencesKeyValueStore by lazy { SharedPreferencesKeyValueStore(prefs) }
    val profileStore: WheelProfileStore by lazy { WheelProfileStore(keyValueStore) }
    val chargerProfileStore: ChargerProfileStore by lazy { ChargerProfileStore(keyValueStore) }
    val appSettingsStore: AppSettingsStore by lazy { AppSettingsStore(keyValueStore) }
    val demoDataProvider: DemoDataProvider by lazy { DemoDataProvider() }
    val alarmChecker: AlarmChecker by lazy { AlarmChecker() }
    val telemetryBuffer: TelemetryBuffer by lazy { TelemetryBuffer() }
    val chargingStationRepository: ChargingStationRepository by lazy {
        ChargingStationRepository(
            source = OpenChargeMapSource(OpenChargeMapClient(apiKey = BuildConfig.OPENCHARGEMAP_API_KEY)),
        )
    }
}