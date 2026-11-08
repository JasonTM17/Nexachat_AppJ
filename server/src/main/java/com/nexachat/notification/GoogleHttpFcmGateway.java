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
 *
 * <p>Google error responses are classified from the structured
 * {@code error.details[].errorCode} and {@code error.status} fields instead of
 * substring matching, because one HTTP status and status word cover both a dead
 * registration token and a malformed request payload: a {@code BAD_REQUEST}
 * detail means the token may still be valid, while an {@code FcmError} detail
 * names the token-level failure that requires removing it.
 */
@Component
@ConditionalOnProperty(name = "nexachat.fcm.enabled", havingValue = "true")
public class GoogleHttpFcmGateway implements FcmGateway {

    private static final Logger log = LoggerFactory.getLogger(GoogleHttpFcmGateway.class);

    /** FCM-specific detail type; its {@code errorCode} is the actionable code. */
    private static final String FCM_ERROR_DETAIL_TYPE = "google.firebase.fcm.v1.FcmError";
    /** Request-shaped error detail: invalid field, payload, TTL or parameters. */
    private static final String BAD_REQUEST_DETAIL_TYPE = "google.rpc.BadRequest";

    /** Google requires at least a one-minute initial delay before retrying a quota rejection. */
    private static final long QUOTA_MIN_RETRY_MILLIS = 60_000L;
    private static final long MAX_RETRY_MILLIS = 5_000L;
    private static final long MAX_RETRY_AFTER_MILLIS = 300_000L;
    private static final long NO_RETRY_AFTER = -1L;

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

        return execute(accessToken, payload).thenCompose(outcome -> {
            PushResult result = outcome.result();
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
                long delayMillis = retryDelayMillis(result.errorCode(), transientAttempt,
                        outcome.retryAfterMillis());
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> sendWithToken(payload, transientAttempt + 1, retriedAfterAuth));
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<SendOutcome> execute(String accessToken, String payload) {
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
            return CompletableFuture.completedFuture(
                    new SendOutcome(PushResult.failure("REQUEST_BUILD_ERROR", false), NO_RETRY_AFTER));
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, ex) -> {
                    if (ex != null) {
                        if (ex instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        log.debug("FCM send transport failure: {}", ex.getClass().getSimpleName());
                        return new SendOutcome(
                                PushResult.failure("TRANSPORT_" + ex.getClass().getSimpleName(), false),
                                NO_RETRY_AFTER);
                    }
                    return new SendOutcome(classify(response), retryAfterMillis(response));
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

        GoogleError error = parseError(response.body());
        if (error.fcmErrorCode() != null) {
            return switch (error.fcmErrorCode()) {
                case "UNREGISTERED", "INVALID_ARGUMENT" -> {
                    log.warn("FCM token no longer valid (HTTP {}), removing it", status);
                    yield PushResult.unregistered(error.fcmErrorCode());
                }
                case "QUOTA_EXCEEDED", "RESOURCE_EXHAUSTED" ->
                        PushResult.failure("RESOURCE_EXHAUSTED", false);
                case "UNAVAILABLE", "INTERNAL" -> PushResult.failure(error.fcmErrorCode(), false);
                default -> PushResult.failure("HTTP_" + status, false);
            };
        }
        if (error.hasFcmErrorDetail() || error.badRequest()) {
            // Either an FCM detail without a usable code, or a request-shaped
            // error: the registration token itself may still be valid.
            log.warn("FCM send rejected without a token-level error code (HTTP {}), device token kept",
                    status);
            return PushResult.failure("HTTP_" + status, false);
        }
        if (status == 404 || "NOT_FOUND".equals(error.status())) {
            log.warn("FCM token no longer valid (HTTP {}), removing it", status);
            return PushResult.unregistered("UNREGISTERED");
        }
        if (status == 429 || "RESOURCE_EXHAUSTED".equals(error.status())) {
            return PushResult.failure("RESOURCE_EXHAUSTED", false);
        }
        log.warn("FCM send failed with HTTP {}", status);
        return PushResult.failure("HTTP_" + status, false);
    }

    /**
     * Reads only the actionable fields of a Google error body. The message text
     * is deliberately ignored: it is not logged and never leaves this method.
     */
    private GoogleError parseError(String body) {
        if (body == null || body.isBlank()) {
            return GoogleError.NONE;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            return GoogleError.NONE;
        }
        if (root == null || !root.isObject()) {
            return GoogleError.NONE;
        }
        JsonNode error = root.get("error");
        if (error == null || !error.isObject()) {
            return GoogleError.NONE;
        }

        String fcmErrorCode = null;
        boolean hasFcmErrorDetail = false;
        boolean badRequest = false;
        JsonNode details = error.get("details");
        if (details != null && details.isArray()) {
            for (JsonNode detail : details) {
                if (detail == null || !detail.isObject()) {
                    continue;
                }
                String type = text(detail.get("@type"));
                if (type == null) {
                    continue;
                }
                if (type.endsWith(FCM_ERROR_DETAIL_TYPE)) {
                    hasFcmErrorDetail = true;
                    if (fcmErrorCode == null) {
                        fcmErrorCode = text(detail.get("errorCode"));
                    }
                } else if (type.endsWith(BAD_REQUEST_DETAIL_TYPE)) {
                    badRequest = true;
                }
            }
        }
        return new GoogleError(fcmErrorCode, hasFcmErrorDetail, text(error.get("status")), badRequest);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
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

    /** Retry-After is honoured in delta-seconds; the HTTP-date form is ignored. */
    private static long retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(GoogleHttpFcmGateway::parseRetryAfter)
                .filter(value -> value >= 0)
                .orElse(NO_RETRY_AFTER);
    }

    private static long parseRetryAfter(String raw) {
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds < 0 ? NO_RETRY_AFTER : seconds * 1000L;
        } catch (Exception ex) {
            return NO_RETRY_AFTER;
        }
    }

    private static boolean isTransient(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return errorCode.startsWith("HTTP_5")
                || errorCode.startsWith("TRANSPORT_")
                || errorCode.equals("RESOURCE_EXHAUSTED")
                || errorCode.equals("UNAVAILABLE")
                || errorCode.equals("INTERNAL");
    }

    /**
     * Delay before the next attempt. A {@code Retry-After} hint wins when
     * present; otherwise a quota rejection honours Google's one-minute floor and
     * other transient errors use the bounded exponential backoff.
     */
    long retryDelayMillis(String errorCode, int attempt, long retryAfterMillis) {
        if (retryAfterMillis >= 0) {
            return Math.min(retryAfterMillis, MAX_RETRY_AFTER_MILLIS);
        }
        long base = Math.min(retryBaseDelayMillis, MAX_RETRY_MILLIS);
        long exponential = base * (1L << Math.min(attempt, 10));
        long jitter = ThreadLocalRandom.current().nextLong(base + 1);
        long delay = Math.min(exponential + jitter, MAX_RETRY_MILLIS);
        if ("RESOURCE_EXHAUSTED".equals(errorCode)) {
            delay = Math.max(delay, QUOTA_MIN_RETRY_MILLIS);
        }
        return delay;
    }

    private static URI resolveApiUrl(FcmProperties properties) {
        String override = properties.getApiUrl();
        if (override != null && !override.isBlank()) {
            return FcmEndpoints.apiUrl(override);
        }
        return FcmEndpoints.googleSendUrl(properties.getProjectId().strip());
    }

    /** Internal send outcome; the retry hint never leaves the gateway. */
    private record SendOutcome(PushResult result, long retryAfterMillis) {
    }

    /** Structured view of a Google error body; only the actionable fields are kept. */
    private record GoogleError(String fcmErrorCode, boolean hasFcmErrorDetail,
                               String status, boolean badRequest) {
        static final GoogleError NONE = new GoogleError(null, false, null, false);
    }

    @Override
    public String toString() {
        return "GoogleHttpFcmGateway{projectId='" + projectId + "', apiUrl='" + apiUri + "'}";
    }
}
