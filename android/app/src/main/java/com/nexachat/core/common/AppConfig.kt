package com.nexachat.core.common

import com.nexachat.BuildConfig

/**
 * Compile-time app configuration.
 *
 * [API_BASE_URL] is baked in at build time from `local.properties`
 * (`API_BASE_URL=...`) or `-PapiBaseUrl=...` — for a LAN demo set the dev
 * machine's IP there and rebuild; no source edit needed. The default,
 * `10.0.2.2`, is the emulator's loopback alias to the host machine.
 * Cleartext HTTP is permitted by the debug-source-set
 * `network_security_config.xml`; release builds are HTTPS-only.
 */
object AppConfig {
    const val API_BASE_URL = BuildConfig.API_BASE_URL
}

/**
 * Server-returned media paths may be absolute (`https://…`) or root-relative
 * (`/api/v1/files/…`); resolve either to an absolute URL for Coil/Retrofit.
 */
fun String.absoluteMediaUrl(): String = when {
    startsWith("http://") || startsWith("https://") -> this
    startsWith("/") -> AppConfig.API_BASE_URL + this
    else -> "${AppConfig.API_BASE_URL}/$this"
}
