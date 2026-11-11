package com.nexachat.firebase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Google OAuth authenticated RTDB REST client with redirects disabled. */
public final class FirebaseRtdbRestClient implements FirebaseRtdbClient {

    public static final String DATABASE_SCOPE = "https://www.googleapis.com/auth/firebase.database";
    public static final String USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";

    private final FirebaseDatabaseUrl databaseUrl;
    private final GoogleCredentials credentials;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public FirebaseRtdbRestClient(FirebaseDatabaseUrl databaseUrl,
                                  GoogleCredentials credentials,
                                  ObjectMapper objectMapper,
                                  Duration connectTimeout,
                                  Duration requestTimeout) {
        this.databaseUrl = databaseUrl;
        this.credentials = credentials.createScoped(Set.of(DATABASE_SCOPE, USERINFO_EMAIL_SCOPE));
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Long readSessionLease(UUID userId, UUID bridgeId) {
        URI uri = databaseUrl.resolve("sessions/" + userId + "/" + bridgeId + "/until");
        String response = send("GET", uri, null);
        JsonNode value;
        try {
            value = objectMapper.readTree(response);
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned an invalid lease value");
        }
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new FirebaseProviderException("Firebase Realtime Database returned an invalid lease value");
        }
        return value.longValue();
    }

    @Override
    public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                  Map<UUID, Long> conversationLeaseUntilMillis) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId + "/" + bridgeId + "/until", leaseUntilMillis);
        conversationLeaseUntilMillis.forEach((conversationId, until) ->
                changes.put("members/" + conversationId + "/" + userId, until));
        patch(changes);
    }

    @Override
    public void deleteBridge(UUID userId, UUID bridgeId) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId + "/" + bridgeId, null);
        changes.put("presence/" + userId + "/" + bridgeId, null);
        patch(changes);
    }

    @Override
    public void revokeUser(UUID userId, List<UUID> conversationIds) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId, null);
        changes.put("presence/" + userId, null);
        for (UUID conversationId : new ArrayList<>(conversationIds)) {
            changes.put("typing/" + conversationId + "/" + userId, null);
        }
        patch(changes);
    }

    private void patch(Map<String, Object> changes) {
        send("PATCH", databaseUrl.rootJson(), changes);
    }

    private String send(String method, URI uri, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + accessToken());
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)));
            } catch (IOException ex) {
                throw new FirebaseProviderException("Firebase Realtime Database request could not be encoded");
            }
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FirebaseProviderException("Firebase Realtime Database request was interrupted");
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database request failed");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FirebaseProviderException(
                    "Firebase Realtime Database returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String accessToken() {
        try {
            credentials.refreshIfExpired();
            AccessToken accessToken = credentials.getAccessToken();
            if (accessToken == null || accessToken.getTokenValue() == null
                    || accessToken.getTokenValue().isBlank()) {
                accessToken = credentials.refreshAccessToken();
            }
            return accessToken.getTokenValue();
        } catch (IOException ex) {
            throw new FirebaseProviderException("Firebase Realtime Database credentials could not be refreshed");
        }
    }
}
