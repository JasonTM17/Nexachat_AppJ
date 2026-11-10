package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local fake of the Google OAuth2 token endpoint and the FCM v1 send endpoint.
 * Generates a throw-away RSA key in memory; no key material is written to disk.
 */
final class FakeGoogleServers implements AutoCloseable {

    record FcmRequest(String authorization, String contentType, String body) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer tokenServer;
    private final HttpServer fcmServer;
    private final KeyPair keyPair;

    private final List<String> tokenRequestBodies = new CopyOnWriteArrayList<>();
    private final List<String> tokenRequestContentTypes = new CopyOnWriteArrayList<>();
    private final List<String> tokenAssertions = new CopyOnWriteArrayList<>();
    private final List<FcmRequest> fcmRequests = new CopyOnWriteArrayList<>();
    private final AtomicLong tokenCounter = new AtomicLong();
    private final AtomicInteger unauthorizedResponsesLeft = new AtomicInteger();
    private final AtomicInteger serverErrorResponsesLeft = new AtomicInteger();
    private final AtomicInteger quotaResponsesLeft = new AtomicInteger();
    private volatile int serverErrorStatus = 500;
    private volatile String fcmRetryAfter;

    private volatile long expiresInSeconds = 3600L;
    private volatile int tokenStatus = 200;
    private volatile String tokenErrorBody =
            "{\"error\":\"invalid_grant\",\"error_description\":\"SECRET-TOKEN-ENDPOINT-BODY\"}";
    private volatile String tokenSuccessBody = null;
    private volatile long tokenDelayMillis = 0L;
    private volatile int fcmStatus = 200;
    private volatile String fcmResponseBody = "{\"name\":\"projects/test-project/messages/1\"}";

    FakeGoogleServers() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();

            this.tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.tokenServer.createContext("/token", this::handleToken);
            this.tokenServer.start();

            this.fcmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.fcmServer.createContext("/v1/projects/test-project/messages:send", this::handleFcm);
            this.fcmServer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not start fake Google servers", ex);
        }
    }

    String tokenUrl() {
        return "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token";
    }

    String fcmUrl() {
        return "http://127.0.0.1:" + fcmServer.getAddress().getPort()
                + "/v1/projects/test-project/messages:send";
    }

    PublicKey publicKey() {
        return keyPair.getPublic();
    }

    String serviceAccountJson() {
        return serviceAccountJson(tokenUrl());
    }

    String serviceAccountJson(String tokenUri) {
        String pem = "-----"
                + "BEGIN "
                + "PRIVATE "
                + "KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----"
                + "END "
                + "PRIVATE "
                + "KEY-----\n";
        return "{\"type\":\"service_account\",\"project_id\":\"test-project\",\"private_key_id\":\"test-key-id\","
                + "\"client_email\":\"test@example.iam.gserviceaccount.com\","
                + "\"client_id\":\"123456789012345678901\","
                + "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
                + "\"token_uri\":\"" + tokenUri + "\","
                + "\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\","
                + "\"client_x509_cert_url\":\"https://www.googleapis.com/robot/v1/metadata/x509/test%40example.iam.gserviceaccount.com\","
                + "\"universe_domain\":\"googleapis.com\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}";
    }

    void setExpiresInSeconds(long seconds) {
        this.expiresInSeconds = seconds;
    }

    void setTokenStatus(int status) {
        this.tokenStatus = status;
    }

    void setTokenErrorBody(String body) {
        this.tokenErrorBody = body;
    }

    void setTokenSuccessBody(String body) {
        this.tokenSuccessBody = body;
    }

    void setTokenDelayMillis(long millis) {
        this.tokenDelayMillis = millis;
    }

    void setFcmStatus(int status) {
        this.fcmStatus = status;
    }

    void setFcmResponseBody(String body) {
        this.fcmResponseBody = body;
    }

    void setUnauthorizedResponses(int count) {
        this.unauthorizedResponsesLeft.set(count);
    }

    void setServerErrorResponses(int count) {
        this.serverErrorStatus = 500;
        this.serverErrorResponsesLeft.set(count);
    }

    void setUnavailableResponses(int count) {
        this.serverErrorStatus = 503;
        this.serverErrorResponsesLeft.set(count);
    }

    void setQuotaResponses(int count) {
        this.quotaResponsesLeft.set(count);
    }

    void setFcmRetryAfter(String value) {
        this.fcmRetryAfter = value;
    }

    List<String> tokenRequestBodies() {
        return List.copyOf(tokenRequestBodies);
    }

    List<String> tokenRequestContentTypes() {
        return List.copyOf(tokenRequestContentTypes);
    }

    List<String> tokenAssertions() {
        return List.copyOf(tokenAssertions);
    }

    List<FcmRequest> fcmRequests() {
        return List.copyOf(fcmRequests);
    }

    @Override
    public void close() {
        tokenServer.stop(0);
        fcmServer.stop(0);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        tokenRequestBodies.add(body);
        tokenRequestContentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        tokenAssertions.add(formValue(body, "assertion"));
        if (tokenDelayMillis > 0) {
            // Deliberate fake-server delay: it holds the token exchange open long
            // enough for concurrent sends to overlap, which is the tested window.
            try {
                Thread.sleep(tokenDelayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        int status = tokenStatus;
        byte[] payload;
        if (status == 200 && tokenSuccessBody == null) {
            String token = "test-access-token-" + tokenCounter.incrementAndGet();
            payload = objectMapper.writeValueAsBytes(Map.of(
                    "access_token", token,
                    "expires_in", expiresInSeconds,
                    "token_type", "Bearer"));
        } else if (status == 200) {
            payload = tokenSuccessBody.getBytes(StandardCharsets.UTF_8);
        } else {
            payload = tokenErrorBody.getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void handleFcm(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        fcmRequests.add(new FcmRequest(authorization, contentType, body));

        int status = fcmStatus;
        if (unauthorizedResponsesLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            status = 401;
        } else if (serverErrorResponsesLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            status = serverErrorStatus;
        } else if (quotaResponsesLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            status = 429;
        }
        byte[] payload = fcmResponseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (fcmRetryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", fcmRetryAfter);
        }
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static String formValue(String form, String key) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
