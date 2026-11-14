package com.nexachat.auth.google;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup behavior of the Google decoder: fail closed when misconfigured, never fetch when off. */
class GoogleSignInConfigurationTest {

    @Test
    void disabledFeatureDoesNotRequireAClientId() {
        assertThatCode(() -> GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(false, "", null, 300, java.util.List.of(), false, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledFeatureWithoutAClientIdFailsStartup() {
        assertThatThrownBy(() -> GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(true, " ", "https://example.invalid/certs", 300,
                        java.util.List.of(), false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-client-id");
    }

    @Test
    void enabledFeatureWithoutTrustedAndroidClientIdsFailsStartup() {
        assertThatThrownBy(() -> GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(true, GoogleTestKeys.AUDIENCE,
                        "https://example.invalid/certs", 300, java.util.List.of(), false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("android-client-ids");
    }

    @Test
    void enabledFeatureBuildsARealDecoderWithoutContactingTheNetwork() {
        JwtDecoder decoder = GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(true, GoogleTestKeys.AUDIENCE,
                        "http://127.0.0.1:1/certs", 300,
                        java.util.List.of(GoogleTestKeys.ANDROID_CLIENT_ID), false, null));

        assertThat(decoder).isNotNull();
    }

    @Test
    void configuredCustomIssuerIsUsedByTheProductionDecoder() throws Exception {
        String customIssuer = "https://identity.example.test";
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = GoogleTestKeys.jwks(java.util.List.of(GoogleTestKeys.keyA()))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        try {
            GoogleSignInProperties properties = new GoogleSignInProperties(
                    true, GoogleTestKeys.AUDIENCE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks", 300,
                    java.util.List.of(GoogleTestKeys.ANDROID_CLIENT_ID), false,
                    java.util.List.of(customIssuer));
            JwtDecoder decoder = GoogleSignInConfiguration.decoderFor(properties);
            String customToken = GoogleTestKeys.token(claims -> {
                claims.issuer = customIssuer;
                claims.nonce = "configured-issuer";
            });
            String googleToken = GoogleTestKeys.token(claims -> {
                claims.issuer = "https://accounts.google.com";
                claims.nonce = "default-issuer";
            });

            assertThat(decoder.decode(customToken).getSubject()).isEqualTo(GoogleTestKeys.SUBJECT);
            assertThatThrownBy(() -> decoder.decode(googleToken)).isInstanceOf(JwtException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blankIssuerConfigurationFailsDuringStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(GoogleSignInConfiguration.class)
                .withPropertyValues(
                        "nexachat.google.signin.enabled=false",
                        "nexachat.google.signin.issuer= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyIssuerConfigurationFailsDuringStartup() {
        assertThatThrownBy(() -> new GoogleSignInProperties(
                false, "", null, 300, java.util.List.of(), false, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void issuerDefaultsBindFromApplicationConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(GoogleSignInConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GoogleSignInProperties.class).issuer())
                            .containsExactly("accounts.google.com", "https://accounts.google.com");
                });
    }

    @Test
    void commaSeparatedIssuerConfigurationBindsAsAList() {
        new ApplicationContextRunner()
                .withUserConfiguration(GoogleSignInConfiguration.class)
                .withPropertyValues(
                        "nexachat.google.signin.enabled=false",
                        "nexachat.google.signin.issuer=https://issuer-one.example,https://issuer-two.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GoogleSignInProperties.class).issuer())
                            .containsExactly("https://issuer-one.example", "https://issuer-two.example");
                });
    }

    @Test
    void jwksHttpClientStopsWaitingWhenTheResponseBodyStalls() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext("/warmup", exchange -> {
            byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/stalled", exchange -> {
            requestCount.incrementAndGet();
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 11);
                requestReceived.countDown();
                releaseResponse.await();
                exchange.getResponseBody().write("{\"keys\":[]}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // The client is expected to close the timed-out response.
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestOperations jwksRestOperations = GoogleSignInConfiguration.jwksRestOperations();
            // Warm up the HTTP client so the timed window is not charged for
            // one-time class loading on a cold JVM.
            jwksRestOperations.getForObject(baseUrl + "/warmup", String.class);

            long startedAt = System.nanoTime();
            var request = executor.submit(() -> jwksRestOperations.getForObject(baseUrl + "/stalled", String.class));
            assertThat(requestReceived.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                request.get(8, TimeUnit.SECONDS);
                throw new AssertionError("JWKS body read unexpectedly completed");
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(ResourceAccessException.class);
            } catch (TimeoutException exception) {
                throw new AssertionError("JWKS response read did not honor its timeout", exception);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertThat(elapsedMillis)
                    .as("JWKS fetch must end within the bounded request deadline")
                    .isLessThan(GoogleSignInConfiguration.JWKS_REQUEST_TIMEOUT.toMillis() + 2_000);
            assertThat(requestCount)
                    .as("JWKS fetch must not retry the request")
                    .hasValue(1);
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void defaultsApplyWhenPropertiesAreOmitted() {
        GoogleSignInProperties properties = new GoogleSignInProperties(
                false, "", "", 0, java.util.List.of(), false, null);

        assertThat(properties.jwksUri()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
        assertThat(properties.nonceTtlSeconds()).isEqualTo(300);
        assertThat(properties.issuer()).containsExactly("accounts.google.com", "https://accounts.google.com");
    }
}
