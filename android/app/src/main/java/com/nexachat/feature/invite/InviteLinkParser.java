package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Accepts only the app's bounded HTTPS invite URL shape. */
public final class InviteLinkParser {
    public static final String HOST = "nexachat.app";
    private static final String PATH_PREFIX = "/join/";
    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private InviteLinkParser() { }

    @NonNull
    public static Optional<ParsedInvite> parse(@Nullable String value) {
        if (value == null || value.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !HOST.equalsIgnoreCase(host)
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || path == null
                    || !path.startsWith(PATH_PREFIX)) {
                return Optional.empty();
            }
            String token = path.substring(PATH_PREFIX.length());
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
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of("https://" + HOST + PATH_PREFIX + token);
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
