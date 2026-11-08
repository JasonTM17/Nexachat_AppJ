package com.nexachat.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code nexachat.fcm.*} from application.yml.
 *
 * <p>The service-account JSON holds a private key: it is bound for the gateway to
 * parse, but {@link #toString()} and every log line must keep it out of output.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.fcm")
public class FcmProperties {

    private boolean enabled;
    private String projectId = "";
    private String serviceAccountJson = "";
    /** Optional token-endpoint override; blank means the URL from the service-account JSON. */
    private String tokenUri = "";
    /** Optional FCM v1 send-endpoint override; blank means the Google endpoint for the project. */
    private String apiUrl = "";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 10;
    private int maxRetries = 2;
    private long retryBaseDelayMillis = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getServiceAccountJson() {
        return serviceAccountJson;
    }

    public void setServiceAccountJson(String serviceAccountJson) {
        this.serviceAccountJson = serviceAccountJson;
    }

    public String getTokenUri() {
        return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBaseDelayMillis() {
        return retryBaseDelayMillis;
    }

    public void setRetryBaseDelayMillis(long retryBaseDelayMillis) {
        this.retryBaseDelayMillis = retryBaseDelayMillis;
    }

    @Override
    public String toString() {
        return "FcmProperties{enabled=" + enabled
                + ", projectId='" + projectId + '\''
                + ", serviceAccountJson=[REDACTED]"
                + ", tokenUri='" + tokenUri + '\''
                + ", apiUrl='" + apiUrl + '\''
                + ", connectTimeoutSeconds=" + connectTimeoutSeconds
                + ", readTimeoutSeconds=" + readTimeoutSeconds
                + ", maxRetries=" + maxRetries
                + ", retryBaseDelayMillis=" + retryBaseDelayMillis + '}';
    }
}
