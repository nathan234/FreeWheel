package com.cooper.wheellog

import android.app.Application
import android.content.res.Configuration
import com.cooper.wheellog.di.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class WheelLog : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@WheelLog)
            modules(settingModule, notificationsModule, volumeKeyModule)
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree(), FileLoggingTree(applicationContext))
        }

        WheelData.initiate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleManager.setLocale(this)
    }
}
