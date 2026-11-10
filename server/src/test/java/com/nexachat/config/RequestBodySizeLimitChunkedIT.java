package com.nexachat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-container regression for the chunked request-body bound.
 *
 * <p>The filter under test caps JSON request bodies at
 * {@code nexachat.security.max-json-body-bytes}. A body sent with
 * {@code Transfer-Encoding: chunked} carries no {@code Content-Length}, so the
 * filter has to count the input stream instead of trusting the header. This IT
 * boots a real server on a random port and sends genuinely chunked requests
 * through {@link HttpClient} with an unknown-length body publisher; the
 * mock-level {@code RequestBodySizeLimitFilterTest} covers the filter in
 * isolation.
 *
 * <p>The limit is lowered to a few kilobytes so the ordinary over-limit probe
 * stays small and the test cannot be confused by the default megabyte ceiling.
 * A second probe sends several megabytes, matching the review's large-body
 * scenario: the server must answer from the counted prefix instead of buffering
 * the whole body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class RequestBodySizeLimitChunkedIT {

    /** Kept small so the over-limit probe is a few kilobytes, not a megabyte. */
    private static final int LIMIT = 4096;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @DynamicPropertySource
    static void chunkedBodyLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("nexachat.security.max-json-body-bytes", () -> String.valueOf(LIMIT));
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedWith413() throws Exception {
        byte[] body = bytes(LIMIT + 1);
        HttpRequest request = requestBuilder("/api/v1/auth/login")
                .POST(unknownLength(body))
                .build();
        assertThat(request.bodyPublisher()).isPresent();
        assertThat(request.bodyPublisher().orElseThrow().contentLength())
                .as("the probe must be sent without a known Content-Length")
                .isEqualTo(-1);

        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        JsonNode error = objectMapper.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(error.path("message").asText())
                .isEqualTo("Request body exceeds the maximum allowed size");
    }

    @Test
    void chunkedBodySeveralMegabytesOverTheLimitIsRejectedWith413() throws Exception {
        byte[] body = bytes(3 * 1024 * 1024);
        HttpRequest request = requestBuilder("/api/v1/auth/login")
                .POST(unknownLength(body))
                .build();

        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(objectMapper.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void chunkedBodyUnderTheLimitStillSucceeds() throws Exception {
        String phone = randomPhone();
        HttpResponse<String> registered = http.send(
                requestBuilder("/api/v1/auth/register")
                        .POST(BodyPublishers.ofByteArray(registerBody(phone)))
                        .build(),
                BodyHandlers.ofString());
        assertThat(registered.statusCode()).isEqualTo(201);

        String login = "{\"phone\":\"" + phone + "\",\"password\":\"secret123\"}";
        HttpRequest request = requestBuilder("/api/v1/auth/login")
                .POST(unknownLength(login.getBytes(StandardCharsets.UTF_8)))
                .build();

        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).path("accessToken").asText())
                .isNotBlank();
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher unknownLength(byte[] body) {
        return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
    }

    private static byte[] registerBody(String phone) {
        return ("{\"phone\":\"" + phone + "\",\"password\":\"secret123\","
                + "\"displayName\":\"Chunked IT\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String randomPhone() {
        return "+849" + String.format("%08d",
                ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static byte[] bytes(int size) {
        byte[] body = new byte[size];
        for (int index = 0; index < size; index++) {
            body[index] = (byte) ('a' + index % 26);
        }
        return body;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
