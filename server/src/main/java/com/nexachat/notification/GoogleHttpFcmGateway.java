package com.nexachat.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * FCM v1 gateway that authenticates with a Google service-account OAuth2 access
 * token. Selected only when {@code nexachat.fcm.enabled=true}; the constructor
 * fails fast when the credentials are missing or malformed.
 */
@Component
@ConditionalOnProperty(name = "nexachat.fcm.enabled", havingValue = "true")
public class GoogleHttpFcmGateway implements FcmGateway {

    private static final Logger log = LoggerFactory.getLogger(GoogleHttpFcmGateway.class);

    private final FcmTokenProvider tokenProvider;
    private final URI apiUri;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final int maxRetries;
    private final long retryBaseDelayMillis;
    private final int readTimeoutSeconds;
    private final String projectId;

    public GoogleHttpFcmGateway(FcmProperties properties, ObjectMapper objectMapper) {
        if (properties.getProjectId() == null || properties.getProjectId().isBlank()) {
            throw new IllegalStateException("FCM is enabled but nexachat.fcm.project-id is empty");
        }
        this.projectId = properties.getProjectId().strip();
        this.objectMapper = objectMapper;
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.retryBaseDelayMillis = Math.max(0, properties.getRetryBaseDelayMillis());
        this.readTimeoutSeconds = Math.max(1, properties.getReadTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .build();
        this.apiUri = resolveApiUrl(properties);
        this.tokenProvider = FcmTokenProvider.create(
                properties, httpClient, objectMapper, Duration.ofSeconds(readTimeoutSeconds));
        log.info("FCM gateway initialised for project {}", projectId);
    }

    @Override
    public CompletableFuture<PushResult> sendDataPush(String targetToken, Map<String, String> data,
                                                      String priority, long ttlSeconds) {
        if (targetToken == null || targetToken.isBlank()) {
            log.warn("FCM send ignored: target token is blank");
            return CompletableFuture.completedFuture(PushResult.failure("INVALID_TARGET", false));
        }

        String payload;
        try {
            payload = buildPayload(targetToken, data, priority, ttlSeconds);
        } catch (Exception ex) {
            log.warn("FCM send failed: request payload could not be built");
            return CompletableFuture.completedFuture(PushResult.failure("SERIALIZATION_ERROR", false));
        }
        return sendWithToken(payload, 0, false);
    }

    private String buildPayload(String targetToken, Map<String, String> data,
                                String priority, long ttlSeconds) throws Exception {
        Map<String, Object> messageBody = new LinkedHashMap<>();
        messageBody.put("token", targetToken);
        messageBody.put("data", data);

        Map<String, Object> androidConfig = new LinkedHashMap<>();
        androidConfig.put("priority", priority != null ? priority : "HIGH");
        androidConfig.put("ttl", ttlSeconds + "s");
        messageBody.put("android", androidConfig);

        return objectMapper.writeValueAsString(Map.of("message", messageBody));
    }

    private CompletableFuture<PushResult> sendWithToken(String payload, int transientAttempt,
                                                        boolean retriedAfterAuth) {
        String accessToken;
        try {
            accessToken = tokenProvider.accessToken();
        } catch (FcmTokenException ex) {
            log.warn("FCM access token unavailable: {}", ex.getMessage());
            return CompletableFuture.completedFuture(PushResult.failure("TOKEN_UNAVAILABLE", false));
        }

        return execute(accessToken, payload).thenCompose(result -> {
            if (result.success() || result.unregistered()) {
                return CompletableFuture.completedFuture(result);
            }
            if ("HTTP_401".equals(result.errorCode()) && !retriedAfterAuth) {
                try {
                    tokenProvider.forceRefreshToken();
                    return sendWithToken(payload, 0, true);
                } catch (FcmTokenException ex) {
                    log.warn("FCM token refresh after 401 failed: {}", ex.getMessage());
                    return CompletableFuture.completedFuture(result);
                }
            }
            if (isTransient(result.errorCode()) && transientAttempt < maxRetries) {
                long delayMillis = retryDelay(transientAttempt);
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> sendWithToken(payload, transientAttempt + 1, retriedAfterAuth));
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<PushResult> execute(String accessToken, String payload) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(apiUri)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception ex) {
            log.warn("FCM send failed: request could not be built");
            return CompletableFuture.completedFuture(PushResult.failure("REQUEST_BUILD_ERROR", false));
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, ex) -> {
                    if (ex != null) {
                        if (ex instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        log.debug("FCM send transport failure: {}", ex.getClass().getSimpleName());
                        return PushResult.failure("TRANSPORT_" + ex.getClass().getSimpleName(), false);
                    }
                    return classify(response);
                });
    }

    private PushResult classify(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return PushResult.success(messageId(response));
        }
        if (status == 401) {
            log.warn("FCM send rejected with HTTP 401 (access token rejected)");
            return PushResult.failure("HTTP_401", false);
        }
        String body = response.body();
        if (status == 404 || containsMarker(body, "UNREGISTERED")
                || containsMarker(body, "NOT_FOUND") || containsMarker(body, "INVALID_ARGUMENT")) {
            log.warn("FCM token no longer valid (HTTP {}), removing it", status);
            return PushResult.unregistered("UNREGISTERED");
        }
        log.warn("FCM send failed with HTTP {}", status);
        return PushResult.failure("HTTP_" + status, false);
    }

    private String messageId(HttpResponse<String> response) {
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode name = root == null ? null : root.get("name");
            if (name != null && name.isTextual() && !name.asText().isBlank()) {
                return name.asText();
            }
        } catch (Exception ex) {
            // Fall through to a synthetic id: the response body is not logged.
        }
        return "fcm-" + System.currentTimeMillis();
    }

    private static boolean containsMarker(String body, String marker) {
        return body != null && body.contains(marker);
    }

    private static boolean isTransient(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return errorCode.startsWith("HTTP_5") || errorCode.startsWith("TRANSPORT_");
    }

    private long retryDelay(int attempt) {
        long exponential = retryBaseDelayMillis * (1L << Math.min(attempt, 10));
        long jitter = ThreadLocalRandom.current().nextLong(retryBaseDelayMillis + 1);
        return Math.min(exponential + jitter, 5_000L);
    }

    private static URI resolveApiUrl(FcmProperties properties) {
        String override = properties.getApiUrl();
        if (override != null && !override.isBlank()) {
            return FcmEndpoints.apiUrl(override);
        }
        return FcmEndpoints.googleSendUrl(properties.getProjectId().strip());
    }

    @Override
    public String toString() {
        return "GoogleHttpFcmGateway{projectId='" + projectId + "', apiUrl='" + apiUri + "'}";
    }
}
