plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nexachat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexachat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 2.0: the compose compiler is a Kotlin compiler plugin
// (org.jetbrains.kotlin.plugin.compose) — no kotlinCompilerExtensionVersion needed.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose (versions managed by the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    // Activity / Navigation / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.bundles.lifecycle)

    // Hilt DI + Compose navigation integration
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room — local source of truth (offline-first, plan §12.2)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    // Network — Retrofit/REST + OkHttp (also used for the STOMP-over-WS client)
    implementation(libs.bundles.network)
    ksp(libs.moshi.kotlin.codegen)

    // Local storage — TokenStore / prefs (plan §12.1 core/storage)
    implementation(libs.androidx.datastore.preferences)

    // Image loading (avatars, media messages)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Media playback — voice/video messages
    implementation(libs.bundles.media3)

    // E.164 phone normalization
    implementation(libs.libphonenumber)

    // Debug-only
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Unit tests
    testImplementation(libs.bundles.unit.test)

    // Instrumented tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
