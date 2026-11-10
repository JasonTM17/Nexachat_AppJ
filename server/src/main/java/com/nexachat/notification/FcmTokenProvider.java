package com.nexachat.notification;

import com.nexachat.google.GoogleServiceAccountCredentials;
import com.nexachat.google.GoogleServiceAccountCredentialsFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains Google OAuth2 access tokens for the FCM v1 scope with a service-account
 * JWT assertion signed by the JDK. Tokens are cached in memory and refreshed
 * single-flight shortly before expiry.
 */
final class FcmTokenProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final long ASSERTION_TTL_SECONDS = 3600L;
    private static final long REFRESH_MARGIN_SECONDS = 300L;
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;

    private final GoogleServiceAccountCredentials credentials;
    private final URI tokenEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration readTimeout;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Token cached;

    private FcmTokenProvider(GoogleServiceAccountCredentials credentials, URI tokenEndpoint,
                             HttpClient httpClient, ObjectMapper objectMapper, Duration readTimeout) {
        this.credentials = credentials;
        this.tokenEndpoint = tokenEndpoint;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.readTimeout = readTimeout;
    }

    static FcmTokenProvider create(FcmProperties properties, HttpClient httpClient,
                                   ObjectMapper objectMapper, Duration readTimeout) {
        GoogleServiceAccountCredentials credentials =
                GoogleServiceAccountCredentialsFactory.load(properties.getServiceAccountJson(), objectMapper);
        String configured = properties.getTokenUri();
        URI tokenEndpoint = configured != null && !configured.isBlank()
                ? FcmEndpoints.tokenEndpoint(configured, true)
                : FcmEndpoints.tokenEndpoint(credentials.tokenUri(), false);
        return new FcmTokenProvider(credentials, tokenEndpoint, httpClient, objectMapper, readTimeout);
    }

    /** Returns a cached token, refreshing it under a lock when close to expiry. */
    String accessToken() {
        Token current = cached;
        if (current != null && !current.isNearExpiry()) {
            return current.value;
        }
        lock.lock();
        try {
            current = cached;
            if (current != null && !current.isNearExpiry()) {
                return current.value;
            }
            current = exchange();
            cached = current;
            return current.value;
        } finally {
            lock.unlock();
        }
    }

    /** Drops the cached token and performs a fresh exchange. */
    String forceRefreshToken() {
        lock.lock();
        try {
            Token fresh = exchange();
            cached = fresh;
            return fresh.value;
        } finally {
            lock.unlock();
        }
    }

    private Token exchange() {
        String assertion = signAssertion();
        String form = "grant_type=" + urlEncode(GRANT_TYPE) + "&assertion=" + urlEncode(assertion);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(tokenEndpoint)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(readTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new FcmTokenException("FCM token endpoint request could not be built");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FcmTokenException("FCM token endpoint request was interrupted");
        } catch (Exception ex) {
            throw new FcmTokenException("FCM token endpoint request failed: " + ex.getClass().getSimpleName());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // The response body is deliberately not included: it can carry the
            // request assertion or provider-specific details.
            throw new FcmTokenException("FCM token endpoint returned HTTP " + response.statusCode());
        }
        return parseTokenResponse(response.body());
    }

    private Token parseTokenResponse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new FcmTokenException("FCM token endpoint returned a malformed response");
        }
        if (root == null || !root.isObject()) {
            throw new FcmTokenException("FCM token endpoint returned a malformed response");
        }
        JsonNode tokenNode = root.get("access_token");
        if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asText().isBlank()) {
            throw new FcmTokenException("FCM token endpoint response has no access_token");
        }
        long expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        JsonNode expiresNode = root.get("expires_in");
        if (expiresNode != null && expiresNode.isNumber()) {
            expiresIn = expiresNode.asLong(DEFAULT_EXPIRES_IN_SECONDS);
        } else if (expiresNode != null && expiresNode.isTextual()) {
            try {
                expiresIn = Long.parseLong(expiresNode.asText().trim());
            } catch (NumberFormatException ex) {
                expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
            }
        }
        if (expiresIn <= 0) {
            expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        }
        long expiry = Instant.now().getEpochSecond() + expiresIn;
        return new Token(tokenNode.asText(), expiry);
    }

    private String signAssertion() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", tokenEndpoint.toString());
        claims.put("iat", now);
        claims.put("exp", now + ASSERTION_TTL_SECONDS);

        try {
            String signingInput = base64Url(objectMapper.writeValueAsBytes(header)) + "."
                    + base64Url(objectMapper.writeValueAsBytes(claims));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(credentials.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (Exception ex) {
            throw new FcmTokenException("FCM JWT assertion could not be signed");
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Token(String value, long expiryEpochSecond) {

        boolean isNearExpiry() {
            return Instant.now().getEpochSecond() >= expiryEpochSecond - REFRESH_MARGIN_SECONDS;
        }

        @Override
        public String toString() {
            return "Token[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "FcmTokenProvider{tokenEndpoint='" + tokenEndpoint + "', credentials=" + credentials + '}';
    }
}
