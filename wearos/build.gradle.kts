plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    compileSdk = 35

    defaultConfig {
        applicationId = "org.freewheel"
        minSdk = 24
        targetSdk = 35
        versionCode = 103
        versionName = "1.0.11"
        multiDexEnabled = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    lint {
        abortOnError = false
    }

    namespace = "org.freewheel"
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.wearable.support)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear)
    implementation(project(":shared"))
    testImplementation(libs.junit)
    compileOnly(libs.wearable.compile)
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
