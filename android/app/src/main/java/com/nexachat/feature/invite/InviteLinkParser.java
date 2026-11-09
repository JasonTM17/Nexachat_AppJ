package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.BuildConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Accepts only the bounded invite URL shapes enabled for this build. */
public final class InviteLinkParser {
    private static final String CUSTOM_SCHEME = "nexachat";
    private static final String CUSTOM_HOST = "join";
    private static final String HTTPS_PATH_PREFIX = "/join/";
    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern DNS_HOST = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    private InviteLinkParser() { }

    @NonNull
    public static Optional<ParsedInvite> parse(@Nullable String value) {
        return parse(value, BuildConfig.INVITE_LINK_HTTPS_HOST);
    }

    @NonNull
    public static Optional<ParsedInvite> parse(
            @Nullable String value, @Nullable String configuredHttpsHost) {
        if (value == null || value.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        String httpsHost = normalizeHttpsHost(configuredHttpsHost);
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean customScheme = CUSTOM_SCHEME.equalsIgnoreCase(scheme);
            boolean configuredHttps = !httpsHost.isEmpty()
                    && "https".equalsIgnoreCase(scheme);
            if (!customScheme && !configuredHttps) {
                return Optional.empty();
            }

            String host = uri.getHost();
            String authority = uri.getRawAuthority();
            String path = uri.getRawPath();
            if (host == null
                    || uri.getPort() != -1
                    || authority == null
                    || !authority.equalsIgnoreCase(host)
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || path == null) {
                return Optional.empty();
            }

            String pathPrefix;
            if (customScheme) {
                if (!CUSTOM_HOST.equalsIgnoreCase(host)) {
                    return Optional.empty();
                }
                pathPrefix = "/";
            } else {
                if (!httpsHost.equalsIgnoreCase(host)) {
                    return Optional.empty();
                }
                pathPrefix = HTTPS_PATH_PREFIX;
            }

            if (!path.startsWith(pathPrefix)) {
                return Optional.empty();
            }
            String token = path.substring(pathPrefix.length());
            if (!TOKEN.matcher(token).matches()) {
                return Optional.empty();
            }
            return Optional.of(new ParsedInvite(token));
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @NonNull
    public static Optional<String> shareUrl(@Nullable String token) {
        return shareUrl(token, BuildConfig.INVITE_LINK_HTTPS_HOST);
    }

    @NonNull
    public static Optional<String> shareUrl(
            @Nullable String token, @Nullable String configuredHttpsHost) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        String httpsHost = normalizeHttpsHost(configuredHttpsHost);
        if (httpsHost.isEmpty()) {
            return Optional.of(CUSTOM_SCHEME + "://" + CUSTOM_HOST + "/" + token);
        }
        return Optional.of("https://" + httpsHost + HTTPS_PATH_PREFIX + token);
    }

    @NonNull
    private static String normalizeHttpsHost(@Nullable String host) {
        if (host == null || !DNS_HOST.matcher(host).matches()) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** The token is intentionally omitted if this value is ever inspected or logged. */
    public static final class ParsedInvite {
        private final String token;

        private ParsedInvite(String token) {
            this.token = token;
        }

        @NonNull
        public String getToken() {
            return token;
        }

        @Override
        public String toString() {
            return "ParsedInvite{token=[redacted]}";
        }
    }
}
