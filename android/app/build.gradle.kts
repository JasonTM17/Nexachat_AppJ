import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Backend base URL for BuildConfig.API_BASE_URL — local.properties
// (`API_BASE_URL=...`) beats the default; -PapiBaseUrl beats both.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val releaseSigningPropertiesFile = rootProject.file("../release/keystore/signing.properties")
val releaseSigningProperties = Properties().apply {
    releaseSigningPropertiesFile.takeIf { it.isFile }
        ?.inputStream()?.use { load(it) }
}
val releaseSigningKeys = listOf(
    "storeFile",
    "keyAlias",
    "keystore.storePassword",
    "keyPassword"
)
if (releaseSigningPropertiesFile.isFile) {
    val missingSigningKeys = releaseSigningKeys.filter {
        releaseSigningProperties.getProperty(it).isNullOrBlank()
    }
    require(missingSigningKeys.isEmpty()) {
        "release/keystore/signing.properties is missing required entries: ${missingSigningKeys.joinToString()}"
    }
    require(rootProject.file("../${releaseSigningProperties.getProperty("storeFile")}").isFile) {
        "The keystore configured in release/keystore/signing.properties does not exist"
    }
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse(localProps.getProperty("API_BASE_URL") ?: "http://10.0.2.2:8080")

android {
    namespace = "com.nexachat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexachat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Backend base URL — override in local.properties (LAN demo) or via
        // -PapiBaseUrl=...; defaults to the emulator's host-loopback alias.
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file("../${releaseSigningProperties.getProperty("storeFile")}")
                storePassword = releaseSigningProperties.getProperty("keystore.storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningPropertiesFile.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    sourceSets {
        // Room's exported schema JSON doubles as an androidTest asset directory
        // so MigrationTestHelper can open a database at an older version.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

gradle.taskGraph.whenReady {
    val releasePackagingRequested = allTasks.any {
        it.path == ":app:assembleRelease" ||
            it.path == ":app:packageRelease" ||
            it.path == ":app:bundleRelease"
    }
    if (releasePackagingRequested && !releaseSigningPropertiesFile.isFile) {
        logger.warn(
            "WARNING: release/keystore/signing.properties is missing; this release artifact is " +
                "signed with the debug key and is for local verification only."
        )
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
    annotationProcessor(libs.androidx.room.compiler)

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

    // In-app audio calls
    implementation(libs.stream.webrtc)

    // E.164 phone normalization
    implementation(libs.libphonenumber)

    // Java-first stack: RxJava3 data layer, Views/XML UI (Compose and coroutines are removed at the end)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.androidx.room.rxjava3)
    implementation(libs.androidx.datastore.rxjava3)
    implementation(libs.kotlinx.coroutines.rx3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    testImplementation(libs.mockito.core)
    testImplementation(libs.androidx.arch.core.testing)

    // Debug-only
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Unit tests
    testImplementation(libs.bundles.unit.test)

    // Instrumented tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}

// Kotlin call sites use named arguments against these Java constructors.
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
