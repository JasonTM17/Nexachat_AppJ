package com.nexachat.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleHttpFcmGatewayTest {

    private static final String DEVICE_TOKEN = "SECRET-DEVICE-TOKEN";
    private static final String PRIVATE_KEY_MARKER = "PRIVATE KEY";
    private static final String ACCESS_TOKEN_MARKER = "test-access-token";
    private static final String TOKEN_BODY_MARKER = "SECRET-TOKEN-ENDPOINT-BODY";
    private static final String CLIENT_EMAIL = "test@example.iam.gserviceaccount.com";

    private FakeGoogleServers servers;
    private FcmProperties properties;
    private GoogleHttpFcmGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        servers = new FakeGoogleServers();
        properties = new FcmProperties();
        properties.setEnabled(true);
        properties.setProjectId("test-project");
        properties.setServiceAccountJson(servers.serviceAccountJson());
        properties.setTokenUri(servers.tokenUrl());
        properties.setApiUrl(servers.fcmUrl());
        properties.setConnectTimeoutSeconds(5);
        properties.setReadTimeoutSeconds(10);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelayMillis(1);
        gateway = newGateway(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servers != null) {
            servers.close();
        }
    }

    private GoogleHttpFcmGateway newGateway(FcmProperties props) {
        return new GoogleHttpFcmGateway(props, new ObjectMapper());
    }

    private PushResult send(String token) throws Exception {
        return gateway.sendDataPush(token, Map.of("type", "NEW_MESSAGE"), "HIGH", 60)
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void sendsBearerTokenOnFcmRequest() throws Exception {
        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(servers.tokenRequestBodies()).hasSize(1);
        assertThat(servers.fcmRequests()).hasSize(1);
        assertThat(servers.fcmRequests().get(0).authorization())
                .isEqualTo("Bearer test-access-token-1");
        assertThat(servers.fcmRequests().get(0).body()).contains("\"type\":\"NEW_MESSAGE\"");
    }

    @Test
    void reusesTokenAcrossSends() throws Exception {
        assertThat(send(DEVICE_TOKEN).success()).isTrue();
        assertThat(send(DEVICE_TOKEN).success()).isTrue();

        assertThat(servers.tokenRequestBodies()).hasSize(1);
        assertThat(servers.fcmRequests()).hasSize(2);
        assertThat(servers.fcmRequests().get(0).authorization())
                .isEqualTo(servers.fcmRequests().get(1).authorization());
    }

    @Test
    void refreshesTokenNearExpiry() throws Exception {
        servers.setExpiresInSeconds(299);

        assertThat(send(DEVICE_TOKEN).success()).isTrue();
        assertThat(send(DEVICE_TOKEN).success()).isTrue();

        assertThat(servers.tokenRequestBodies()).hasSize(2);
        assertThat(servers.fcmRequests().get(0).authorization())
                .isNotEqualTo(servers.fcmRequests().get(1).authorization());
    }

    @Test
    void refreshesOnceAndRetriesOn401() throws Exception {
        servers.setUnauthorizedResponses(1);

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(servers.fcmRequests()).hasSize(2);
        assertThat(servers.fcmRequests().get(0).authorization())
                .isEqualTo("Bearer test-access-token-1");
        assertThat(servers.fcmRequests().get(1).authorization())
                .isEqualTo("Bearer test-access-token-2");
        assertThat(servers.tokenRequestBodies()).hasSize(2);
    }

    @Test
    void doesNotRetry401Twice() throws Exception {
        servers.setUnauthorizedResponses(2);

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HTTP_401");
        assertThat(servers.fcmRequests()).hasSize(2);
        assertThat(servers.tokenRequestBodies()).hasSize(2);
    }

    @Test
    void jwtAssertionCarriesServiceAccountClaimsAndValidSignature() throws Exception {
        assertThat(send(DEVICE_TOKEN).success()).isTrue();

        assertThat(servers.tokenAssertions()).hasSize(1);
        String[] parts = servers.tokenAssertions().get(0).split("\\.");
        assertThat(parts).hasSize(3);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");

        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("iss").asText()).isEqualTo(CLIENT_EMAIL);
        assertThat(claims.get("scope").asText())
                .isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(claims.get("aud").asText()).isEqualTo(servers.tokenUrl());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600L);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(servers.publicKey());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(signature.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void notFoundTokenReturnsUnregisteredResult() throws Exception {
        servers.setFcmStatus(404);
        servers.setFcmResponseBody("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}");

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.unregistered()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void invalidArgumentReturnsUnregisteredResult() throws Exception {
        servers.setFcmStatus(400);
        servers.setFcmResponseBody(
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad token\"}}");

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.unregistered()).isTrue();
    }

    @Test
    void retriesTransientServerError() throws Exception {
        servers.setServerErrorResponses(2);

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(servers.fcmRequests()).hasSize(3);
    }

    @Test
    void givesUpAfterBoundedRetries() throws Exception {
        servers.setFcmStatus(500);

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HTTP_500");
        assertThat(servers.fcmRequests()).hasSize(3);
    }

    @Test
    void tokenEndpointFailureReturnsFailure() throws Exception {
        servers.setTokenStatus(500);
        GoogleHttpFcmGateway freshGateway = newGateway(properties);
        gateway = freshGateway;

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOKEN_UNAVAILABLE");
    }

    @Test
    void malformedTokenResponseReturnsFailure() throws Exception {
        servers.setTokenSuccessBody("not-json");
        gateway = newGateway(properties);

        PushResult result = send(DEVICE_TOKEN);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOKEN_UNAVAILABLE");
    }

    @Test
    void malformedServiceAccountJsonFailsFast() {
        properties.setServiceAccountJson("not-json");

        assertThatThrownBy(() -> newGateway(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void missingCredentialsFailFast() {
        properties.setServiceAccountJson("");

        assertThatThrownBy(() -> newGateway(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account-json");
    }

    @Test
    void missingProjectIdFailsFast() {
        properties.setProjectId("");

        assertThatThrownBy(() -> newGateway(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-id");
    }

    @Test
    void rejectsNonGoogleTokenEndpoint() {
        properties.setServiceAccountJson(servers.serviceAccountJson("https://evil.example.com/token"));
        properties.setTokenUri("");

        assertThatThrownBy(() -> newGateway(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth2.googleapis.com");
    }

    @Test
    void rejectsInsecureTokenEndpoint() {
        properties.setServiceAccountJson(servers.serviceAccountJson("http://oauth2.googleapis.com/token"));
        properties.setTokenUri("");

        assertThatThrownBy(() -> newGateway(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void readsServiceAccountJsonFromPath() throws Exception {
        Path tempFile = Files.createTempFile("fcm-service-account", ".json");
        try {
            Files.writeString(tempFile, servers.serviceAccountJson(), StandardCharsets.UTF_8);
            properties.setServiceAccountJson(tempFile.toString());
            gateway = newGateway(properties);

            PushResult result = send(DEVICE_TOKEN);

            assertThat(result.success()).isTrue();
            assertThat(servers.fcmRequests().get(0).authorization())
                    .isEqualTo("Bearer test-access-token-1");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void redactsServiceAccountJsonInToString() throws Exception {
        FcmProperties props = new FcmProperties();
        props.setServiceAccountJson("{\"private_key\":\"FAKE-REDACTION-PROBE\"}");
        assertThat(props.toString())
                .doesNotContain("FAKE-REDACTION-PROBE")
                .doesNotContain("private_key");

        ServiceAccountCredentials credentials =
                ServiceAccountCredentials.load(servers.serviceAccountJson(), new ObjectMapper());
        assertThat(credentials.toString())
                .doesNotContain(PRIVATE_KEY_MARKER)
                .doesNotContain(CLIENT_EMAIL)
                .doesNotContain("FAKE-REDACTION-PROBE");
    }

    @Test
    void noSecretsAppearInLogs() throws Exception {
        ch.qos.logback.classic.Logger gatewayLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GoogleHttpFcmGateway.class);
        ch.qos.logback.classic.Logger tokenLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FcmTokenProvider.class);
        ch.qos.logback.classic.Level gatewayLevel = gatewayLogger.getLevel();
        ch.qos.logback.classic.Level tokenLevel = tokenLogger.getLevel();
        gatewayLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        tokenLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
        tokenLogger.addAppender(appender);
        try {
            // Success path.
            assertThat(send(DEVICE_TOKEN).success()).isTrue();

            // 401 -> refresh -> retry.
            servers.setUnauthorizedResponses(1);
            assertThat(send(DEVICE_TOKEN).success()).isTrue();

            // Token endpoint failure with a secret-bearing body.
            servers.setTokenStatus(500);
            gateway = newGateway(properties);
            assertThat(send(DEVICE_TOKEN).success()).isFalse();
            servers.setTokenStatus(200);

            // Unregistered token.
            servers.setFcmStatus(404);
            servers.setFcmResponseBody("{\"error\":{\"status\":\"NOT_FOUND\"}}");
            gateway = newGateway(properties);
            assertThat(send(DEVICE_TOKEN).unregistered()).isTrue();
            servers.setFcmStatus(200);

            // Transient server error -> bounded retries.
            servers.setFcmStatus(500);
            gateway = newGateway(properties);
            assertThat(send(DEVICE_TOKEN).success()).isFalse();
        } finally {
            gatewayLogger.detachAppender(appender);
            tokenLogger.detachAppender(appender);
            gatewayLogger.setLevel(gatewayLevel);
            tokenLogger.setLevel(tokenLevel);
        }

        assertThat(appender.list)
                .as("logs must not carry credentials")
                .noneMatch(event -> {
                    String text = eventText(event);
                    return text.contains(DEVICE_TOKEN)
                            || text.contains(ACCESS_TOKEN_MARKER)
                            || text.contains(TOKEN_BODY_MARKER)
                            || text.contains(PRIVATE_KEY_MARKER)
                            || text.contains(CLIENT_EMAIL);
                });
    }

    @Test
    void concurrentSendsShareASingleTokenExchange() throws Exception {
        servers.setTokenDelayMillis(50);
        int senders = 20;
        ExecutorService executor = Executors.newFixedThreadPool(senders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PushResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < senders; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return gateway.sendDataPush("concurrent-token-" + index,
                            Map.of("type", "NEW_MESSAGE"), "HIGH", 60).get(10, TimeUnit.SECONDS);
                }));
            }
            start.countDown();
            for (Future<PushResult> future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS).success()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(servers.tokenRequestBodies()).hasSize(1);
        assertThat(servers.fcmRequests()).hasSize(senders);
    }

    private static String eventText(ch.qos.logback.classic.spi.ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
            text.append(' ').append(event.getThrowableProxy().getMessage());
        }
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                if (argument != null) {
                    text.append(' ').append(argument);
                }
            }
        }
        return text.toString();
    }
}
