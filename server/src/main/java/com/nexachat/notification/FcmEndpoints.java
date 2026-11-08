package com.nexachat.notification;

import java.net.URI;
import java.util.Locale;

/**
 * URL validation for the two outbound FCM endpoints. The token endpoint is held
 * to Google's production host; the send endpoint only has to be a plain
 * http(s) URL. Loopback http is accepted so tests can run a local fake.
 */
final class FcmEndpoints {

    static final String DEFAULT_TOKEN_HOST = "oauth2.googleapis.com";
    static final String DEFAULT_SEND_HOST = "fcm.googleapis.com";

    private FcmEndpoints() {
    }

    static URI tokenEndpoint(String raw, boolean override) {
        URI uri = parse(raw, "FCM token endpoint");
        String scheme = scheme(uri);
        String host = uri.getHost();
        if (override) {
            if (scheme.equals("http") && !isLoopback(host)) {
                throw new IllegalStateException("FCM token endpoint must be https unless it points at loopback");
            }
        } else {
            if (!scheme.equals("https")) {
                throw new IllegalStateException("FCM token endpoint must use https");
            }
            if (!DEFAULT_TOKEN_HOST.equals(host)) {
                throw new IllegalStateException("FCM token endpoint must be hosted at " + DEFAULT_TOKEN_HOST);
            }
        }
        return uri;
    }

    static URI apiUrl(String raw) {
        URI uri = parse(raw, "FCM API URL");
        String scheme = scheme(uri);
        String host = uri.getHost();
        if (scheme.equals("http") && !isLoopback(host)) {
            throw new IllegalStateException("FCM API URL must be https unless it points at loopback");
        }
        return uri;
    }

    static URI googleSendUrl(String projectId) {
        return URI.create("https://" + DEFAULT_SEND_HOST + "/v1/projects/" + projectId + "/messages:send");
    }

    private static URI parse(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(label + " is empty");
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (Exception ex) {
            throw new IllegalStateException(label + " is not a valid URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(label + " is not a valid URL");
        }
        String scheme = scheme(uri);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalStateException(label + " must use http or https");
        }
        return uri;
    }

    private static String scheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
