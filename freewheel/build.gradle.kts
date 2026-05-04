import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
        }
    }

    defaultConfig {
        applicationId = "org.freewheel"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val buildTime = SimpleDateFormat("HH:mm dd.MM.yyyy").format(Date())
        val buildDate = SimpleDateFormat("dd.MM.yyyy").format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        // Diagnostics email — placeholder; swap to a real forwarding alias before release.
        buildConfigField("String", "SUPPORT_EMAIL", "\"support@freewheel.invalid\"")

        // Google Maps API key from local.properties (not committed to git)
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())
        val mapsKey = localProps.getProperty("MAPS_API_KEY", "")
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey

        // OpenChargeMap key (see https://openchargemap.org/site/develop/api) for charger POIs
        val openChargeMapKey = localProps.getProperty("OPENCHARGEMAP_API_KEY", "")
        buildConfigField("String", "OPENCHARGEMAP_API_KEY", "\"$openChargeMapKey\"")

        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        unitTests.all {
            it.jvmArgs("-Xmx2g")
        }
    }

    packaging {
        jniLibs {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    lint {
        abortOnError = false
        disable += "ComposableNaming"
    }

    namespace = "org.freewheel"
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core"))
    // Compose
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.compose.icons.extended)
    // Charting
    implementation(libs.vico.compose.m3)
    // Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    // BLE
    implementation(libs.blessed.android)
    // WearOS
    implementation(libs.play.services.wearable)
    // Common
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.timber)
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
