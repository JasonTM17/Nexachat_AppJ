package com.nexachat.core.common;

import com.nexachat.BuildConfig;

/**
 * Compile-time app configuration.
 *
 * {@link #API_BASE_URL} is baked in at build time from {@code local.properties}
 * ({@code API_BASE_URL=...}) or {@code -PapiBaseUrl=...}. The default,
 * {@code 10.0.2.2}, is the emulator's loopback alias to the host machine.
 * Cleartext HTTP is permitted by the debug-source-set network security config;
 * release builds are HTTPS-only.
 */
public final class AppConfig {

    private AppConfig() {
    }

    public static final String API_BASE_URL = BuildConfig.API_BASE_URL;

    /**
     * Server-returned media paths may be absolute ({@code https://…}) or
     * root-relative ({@code /api/v1/files/…}); resolve either to an absolute URL.
     */
    public static String absoluteMediaUrl(String raw) {
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("/")) {
            return API_BASE_URL + raw;
        }
        return API_BASE_URL + "/" + raw;
    }
}
