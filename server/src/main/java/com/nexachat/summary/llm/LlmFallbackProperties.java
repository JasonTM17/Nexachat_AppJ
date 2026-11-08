package com.nexachat.summary.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Fallback cloud provider for the summary and ask LLM paths. It is disabled
 * by default and becomes usable only when an operator key is configured, so a
 * deployment with an empty fallback balance never calls it.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.summary.llm-fallback")
public class LlmFallbackProperties {

    private boolean enabled;
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-v4.1-flash";
    private String apiKey = "";
    private int timeoutMs = 30_000;

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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isUsable() {
        return enabled
                && hasApiKey()
                && apiKey.length() <= 4_096
                && model != null
                && !model.isBlank()
                && model.length() <= 128
                && timeoutMs >= 100
                && timeoutMs <= 120_000
                && endpoint() != null;
    }

    URI endpoint() {
        return LlmEndpoints.chatCompletions(baseUrl, false);
    }

    @Override
    public String toString() {
        return "LlmFallbackProperties{enabled=" + enabled + ", baseUrl='" + baseUrl + '\''
                + ", model='" + model + "', timeoutMs=" + timeoutMs + '}';
    }
}
