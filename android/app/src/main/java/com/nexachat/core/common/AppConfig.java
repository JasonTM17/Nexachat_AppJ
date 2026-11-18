package com.nexachat.core.common;

import androidx.annotation.Nullable;
import com.nexachat.BuildConfig;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

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

    /** Anything that looks like {@code scheme:...} but is not a parsable http(s) URL. */
    private static final Pattern NON_HTTP_SCHEME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    /**
     * Server-returned media paths may be absolute ({@code https://…}) or
     * root-relative ({@code /api/v1/files/…}); resolve either to an absolute URL.
     *
     * <p>Absolute URLs are only passed through when they are on the API origin
     * (same scheme, host and port as {@link #API_BASE_URL} — the same policy the
     * authenticated media loader pins to). Any other absolute URL, and any
     * non-http(s) scheme ({@code file:}, {@code content:}, {@code data:}, …),
     * is rejected with {@code null} so callers fall back to a placeholder.
     * Server-supplied values are untrusted input; without this check a peer- or
     * server-chosen URL would make the client fetch (and ping) an arbitrary host.
     */
    @Nullable
    public static String absoluteMediaUrl(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(raw);
        HttpUrl base = HttpUrl.parse(API_BASE_URL);
        if (parsed != null) {
            if (!parsed.username().isEmpty() || !parsed.password().isEmpty()) {
                return null;
            }
            return base != null && isSameOrigin(parsed, base) ? raw : null;
        }
        if (raw.startsWith("/")) {
            return API_BASE_URL + raw;
        }
        if (NON_HTTP_SCHEME.matcher(raw).matches()) {
            return null;
        }
        return API_BASE_URL + "/" + raw;
    }

    /** True only when {@code target} shares scheme, host and port with {@code base}. */
    public static boolean isSameOrigin(@Nullable HttpUrl target, @Nullable HttpUrl base) {
        return target != null && base != null
                && target.scheme().equals(base.scheme())
                && target.host().equals(base.host())
                && target.port() == base.port();
    }
}
