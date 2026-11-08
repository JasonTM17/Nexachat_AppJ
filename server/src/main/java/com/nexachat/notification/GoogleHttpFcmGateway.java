package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "nexachat.fcm.enabled", havingValue = "true")
public class GoogleHttpFcmGateway implements FcmGateway {

    private static final Logger log = LoggerFactory.getLogger(GoogleHttpFcmGateway.class);

    private final String projectId;
    private final String serviceAccountJson;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleHttpFcmGateway(
            @Value("${nexachat.fcm.project-id:}") String projectId,
            @Value("${nexachat.fcm.service-account-json:}") String serviceAccountJson,
            ObjectMapper objectMapper) {
        this.projectId = projectId;
        this.serviceAccountJson = serviceAccountJson;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public CompletableFuture<PushResult> sendDataPush(String targetToken, Map<String, String> data,
                                                      String priority, long ttlSeconds) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("FCM project ID not configured");
            return CompletableFuture.completedFuture(PushResult.failure("CONFIG_MISSING", false));
        }

        try {
            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put("token", targetToken);
            messageBody.put("data", data);

            Map<String, Object> androidConfig = new HashMap<>();
            androidConfig.put("priority", priority != null ? priority : "HIGH");
            androidConfig.put("ttl", ttlSeconds + "s");
            messageBody.put("android", androidConfig);

            Map<String, Object> payload = Map.of("message", messageBody);
            String json = objectMapper.writeValueAsString(payload);

            String url = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            return PushResult.success("fcm-" + System.currentTimeMillis());
                        } else if (status == 404 || response.body().contains("UNREGISTERED") || response.body().contains("NOT_FOUND")) {
                            log.warn("FCM token unregistered/not found: {}", targetToken);
                            return PushResult.unregistered("UNREGISTERED");
                        } else {
                            log.warn("FCM send failed with HTTP {}: {}", status, response.body());
                            return PushResult.failure("HTTP_" + status, false);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("FCM send exception for token {}: {}", targetToken, ex.getMessage());
                        return PushResult.failure("EXCEPTION_" + ex.getClass().getSimpleName(), false);
                    });
        } catch (Exception ex) {
            log.error("Failed to build FCM request: {}", ex.getMessage());
            return CompletableFuture.completedFuture(PushResult.failure("SERIALIZATION_ERROR", false));
        }
    }
}
