package com.nexachat.summary.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

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

    // Dedicated Local Server Model Tier (e.g. Ollama, vLLM, local server container)
    private boolean localEnabled;
    private String localBaseUrl = "http://localhost:11434/v1";
    private String localModel = "qwen2.5:3b";
    private String localApiKey = "local-ollama";
    private int localTimeoutMs = 60_000;

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

    public boolean isLocalEnabled() {
        return localEnabled;
    }

    public void setLocalEnabled(boolean localEnabled) {
        this.localEnabled = localEnabled;
    }

    public String getLocalBaseUrl() {
        return localBaseUrl;
    }

    public void setLocalBaseUrl(String localBaseUrl) {
        this.localBaseUrl = localBaseUrl;
    }

    public String getLocalModel() {
        return localModel;
    }

    public void setLocalModel(String localModel) {
        this.localModel = localModel;
    }

    @JsonIgnore
    public String getLocalApiKey() {
        return localApiKey;
    }

    public void setLocalApiKey(String localApiKey) {
        this.localApiKey = localApiKey;
    }

    public int getLocalTimeoutMs() {
        return localTimeoutMs;
    }

    public void setLocalTimeoutMs(int localTimeoutMs) {
        this.localTimeoutMs = localTimeoutMs;
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
                && maxInputChars >= 1
                && maxInputChars <= 24_000
                && maxOutputTokens >= 1
                && maxOutputTokens <= 4_000
                && endpoint() != null;
    }

    public boolean isLocalUsable() {
        return localEnabled
                && localModel != null
                && !localModel.isBlank()
                && localModel.length() <= 128
                && localTimeoutMs >= 100
                && localTimeoutMs <= 300_000
                && localEndpoint() != null;
    }

    public boolean hasAnyLlm() {
        return isUsable() || isLocalUsable();
    }

    public URI localEndpoint() {
        return LlmEndpoints.chatCompletions(localBaseUrl, true);
    }

    URI endpoint() {
        return LlmEndpoints.chatCompletions(baseUrl, false);
    }

    @Override
    public String toString() {
        return "LlmProperties{enabled=" + enabled + ", baseUrl='" + baseUrl + '\'' + ", model='" + model + '\''
                + ", localEnabled=" + localEnabled + ", localBaseUrl='" + localBaseUrl + '\''
                + ", localModel='" + localModel + '\'' + '}';
    }
}
