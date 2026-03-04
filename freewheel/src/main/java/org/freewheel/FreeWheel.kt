package org.freewheel

import android.app.Application
import org.freewheel.di.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class FreeWheel : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@FreeWheel)
            modules(settingModule, dbModule)
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree(), EventsLoggingTree(applicationContext))
        }
    }
}
