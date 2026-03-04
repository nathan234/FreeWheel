package org.freewheel.di

import org.freewheel.AppConfig
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val settingModule = module {
    single { AppConfig(androidApplication()) }
}