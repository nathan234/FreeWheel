package org.freewheel

import android.app.Application
import java.io.File
import org.freewheel.compose.di.AppModule
import org.freewheel.core.diagnostics.Diagnostics
import org.freewheel.core.diagnostics.DiagnosticLogStore
import timber.log.Timber

class FreeWheel : Application() {
    override fun onCreate() {
        super.onCreate()
        AppModule.initialize(this)
        initDiagnostics()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree(), EventsLoggingTree(applicationContext))
        }
    }

    private fun initDiagnostics() {
        val store = DiagnosticLogStore()
        val dir = File(getExternalFilesDir(null), "diagnostics")
        store.configure(dir.absolutePath)
        Diagnostics.init(store)
    }
}
