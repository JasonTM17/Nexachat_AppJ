package com.nexachat.core.common

/**
 * Temporary compile-time app configuration.
 *
 * TODO: replace [API_BASE_URL] with a `BuildConfig.API_BASE_URL` field once the
 * field is declared in `app/build.gradle.kts` per build type/product flavor.
 *
 * Dev-only note: `10.0.2.2` is the Android emulator's loopback alias to the
 * host machine, so this points the app at a backend running on the developer's
 * workstation. Cleartext HTTP is allowed by `network_security_config.xml`.
 */
object AppConfig {
    const val API_BASE_URL = "http://10.0.2.2:8080"
}
