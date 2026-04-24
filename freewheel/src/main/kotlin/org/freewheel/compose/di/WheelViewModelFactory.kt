package org.freewheel.compose.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.service.DemoDataProvider

class WheelViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return WheelViewModel(
            application = application,
            appConfig = AppModule.appConfig,
            prefs = AppModule.prefs,
            vibrator = AppModule.vibrator,
            tripRepository = AppModule.tripRepository,
            rideLogger = AppModule.rideLogger,
            captureLogger = AppModule.bleCaptureLogger,
            telemetryFileIO = AppModule.telemetryFileIO,
            profileStore = AppModule.profileStore,
            chargerProfileStore = AppModule.chargerProfileStore,
            demoDataProvider = AppModule.demoDataProvider,
            alarmChecker = AppModule.alarmChecker,
            telemetryBuffer = AppModule.telemetryBuffer,
            chargingStationRepository = AppModule.chargingStationRepository
        ) as T
    }
}
