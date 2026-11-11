package com.nexachat.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** S3-compatible endpoint and credentials; its string representation never includes credentials. */
@ConfigurationProperties(prefix = "nexachat.storage.s3")
public record S3StorageProperties(
        String endpoint,
        String region,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        Duration apiCallTimeout,
        Duration apiCallAttemptTimeout,
        Duration connectionTimeout,
        Duration socketTimeout) {

    /** Default overall deadline for one object-store call, including retries. */
    static final Duration DEFAULT_API_CALL_TIMEOUT = Duration.ofSeconds(60);
    /** Default deadline for one object-store attempt. */
    static final Duration DEFAULT_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    /** Default TCP connect deadline. */
    static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    /** Default socket read-inactivity deadline. */
    static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);

    public S3StorageProperties {
        endpoint = normalized(endpoint);
        region = region == null || region.isBlank() ? "auto" : region.strip();
        bucket = normalized(bucket);
        accessKeyId = normalized(accessKeyId);
        secretAccessKey = normalized(secretAccessKey);

        List<String> missing = new ArrayList<>();
        if (endpoint.isEmpty()) {
            missing.add("NEXACHAT_STORAGE_S3_ENDPOINT");
        }
        if (bucket.isEmpty()) {
            missing.add("NEXACHAT_STORAGE_S3_BUCKET");
        }
        if (accessKeyId.isEmpty()) {
            missing.add("NEXACHAT_STORAGE_S3_ACCESS_KEY_ID");
        }
        if (secretAccessKey.isEmpty()) {
            missing.add("NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("S3 storage selected but required settings are missing: "
                    + String.join(", ", missing));
        }

        URI endpointUri;
        try {
            endpointUri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("NEXACHAT_STORAGE_S3_ENDPOINT must be an absolute HTTP(S) URL");
        }
        String scheme = endpointUri.getScheme();
        if (endpointUri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpointUri.getUserInfo() != null
                || endpointUri.getQuery() != null
                || endpointUri.getFragment() != null) {
            throw new IllegalStateException("NEXACHAT_STORAGE_S3_ENDPOINT must be an absolute HTTP(S) URL");
        }

        apiCallTimeout = positiveOrDefault(apiCallTimeout, DEFAULT_API_CALL_TIMEOUT, "api-call-timeout");
        apiCallAttemptTimeout = positiveOrDefault(apiCallAttemptTimeout,
                DEFAULT_API_CALL_ATTEMPT_TIMEOUT, "api-call-attempt-timeout");
        connectionTimeout = positiveOrDefault(connectionTimeout,
                DEFAULT_CONNECTION_TIMEOUT, "connection-timeout");
        socketTimeout = positiveOrDefault(socketTimeout, DEFAULT_SOCKET_TIMEOUT, "socket-timeout");
        if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalStateException(
                    "nexachat.storage.s3.api-call-attempt-timeout must not exceed api-call-timeout");
        }
    }

    public URI endpointUri() {
        return URI.create(endpoint);
    }

    @Override
    public String toString() {
        return "S3StorageProperties[endpointConfigured=" + !endpoint.isEmpty()
                + ", region=" + region
                + ", bucketConfigured=" + !bucket.isEmpty()
                + ", accessKeyConfigured=" + !accessKeyId.isEmpty()
                + ", secretAccessKey=<redacted>"
                + ", apiCallTimeout=" + apiCallTimeout
                + ", apiCallAttemptTimeout=" + apiCallAttemptTimeout
                + ", connectionTimeout=" + connectionTimeout
                + ", socketTimeout=" + socketTimeout + "]";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException("nexachat.storage.s3." + name + " must be positive");
        }
        return value;
    }
}
