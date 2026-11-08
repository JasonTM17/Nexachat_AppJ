package com.nexachat.summary.llm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Shared OpenAI-compatible endpoint validation. The primary and fallback
 * providers must accept exactly the same base-URL shapes so a fallback can
 * never widen the trust boundary of the primary.
 */
final class LlmEndpoints {

    private LlmEndpoints() {
    }

    /**
     * Builds the {@code /chat/completions} endpoint for a base URL. Plain
     * {@code http} is accepted only for loopback hosts unless
     * {@code allowPlainHttp} is set (the local server model tier).
     */
    static URI chatCompletions(String baseUrl, boolean allowPlainHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI base = new URI(baseUrl.trim());
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            String host = base.getHost();
            if (host == null || host.isBlank() || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                return null;
            }
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (scheme.equals("http") && !allowPlainHttp && !isLoopback(host)) {
                return null;
            }
            String path = base.getPath() == null ? "" : base.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!path.endsWith("/chat/completions")) {
                path += "/chat/completions";
            }
            return new URI(scheme, null, host, base.getPort(), path, null, null);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
