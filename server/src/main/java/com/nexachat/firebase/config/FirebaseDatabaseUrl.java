package com.nexachat.firebase.config;

import java.net.URI;
import java.util.Locale;

/** Validated root URL for one Firebase Realtime Database instance. */
public final class FirebaseDatabaseUrl {

    private final URI root;

    private FirebaseDatabaseUrl(URI root) {
        this.root = root;
    }

    public static FirebaseDatabaseUrl parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("FIREBASE_DATABASE_URL must be a Firebase Realtime Database URL");
        }
        URI parsed;
        try {
            parsed = URI.create(configured.strip());
        } catch (IllegalArgumentException ex) {
            throw invalidUrl();
        }
        String host = parsed.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean firebaseHost = normalizedHost.endsWith(".firebaseio.com")
                || normalizedHost.endsWith(".firebasedatabase.app");
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || !firebaseHost
                || parsed.getUserInfo() != null
                || parsed.getPort() != -1
                || !(parsed.getRawPath() == null || parsed.getRawPath().isEmpty() || "/".equals(parsed.getRawPath()))
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw invalidUrl();
        }
        try {
            return new FirebaseDatabaseUrl(new URI("https", null, normalizedHost, -1, "", null, null));
        } catch (Exception ex) {
            throw invalidUrl();
        }
    }

    public String value() {
        return root.toString();
    }

    URI root() {
        return root;
    }

    public URI resolve(String relativePath) {
        if (relativePath == null || !relativePath.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Firebase database path is invalid");
        }
        return root.resolve(relativePath + ".json");
    }

    public URI rootJson() {
        return root.resolve(".json");
    }

    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException(
                "FIREBASE_DATABASE_URL must be an HTTPS Firebase Realtime Database root URL");
    }

    @Override
    public String toString() {
        return value();
    }
}
