package com.nexachat.summary.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "nexachat.summary.llm")
public class LlmProperties {

    private boolean enabled;
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
    private String apiKey = "";
    private int timeoutMs = 30_000;
    private int maxInputChars = 24_000;
    private int maxOutputTokens = 4_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @JsonIgnore
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    boolean isUsable() {
        return enabled
                && hasApiKey()
                && apiKey.length() <= 4_096
                && model != null
                && !model.isBlank()
                && model.length() <= 128
                && timeoutMs >= 100
                && timeoutMs <= 120_000
                && maxInputChars >= 1
                && maxInputChars <= 24_000
                && maxOutputTokens >= 1
                && maxOutputTokens <= 4_000
                && endpoint() != null;
    }

    URI endpoint() {
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
            if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(host))) {
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

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
