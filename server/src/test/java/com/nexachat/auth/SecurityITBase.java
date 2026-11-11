package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.TestAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared base for the server security integration tests.
 *
 * <p>Points the {@code it} profile at the shared disposable test database
 * ({@code nexachat_test}, overridable through {@code IT_DB_URL}) and seeds the
 * security configuration under test: a single CORS allow-list entry and a
 * single admin phone. Every test starts from an empty user table so the cases
 * are order-independent.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
abstract class SecurityITBase {

    /** Phone the security configuration designates as the RAG-stats admin. */
    protected static final String ADMIN_PHONE = "+84900000001";

    /** Origin the security configuration admits for cross-origin requests. */
    protected static final String ALLOWED_ORIGIN = "https://app.example.com";

    /** Bound on one TRUNCATE attempt; a transient writer releases its locks sooner. */
    private static final int RESET_LOCK_TIMEOUT_MS = 5_000;

    /** Attempts before the reset fails loudly instead of hanging the suite. */
    private static final int RESET_MAX_ATTEMPTS = 4;

    @DynamicPropertySource
    static void securityItProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        // The suite caches one Spring context per unique configuration; a small
        // pool keeps this context from holding a share of the shared database's
        // 100-connection budget (the integration tests run in one JVM).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("nexachat.security.cors-allowed-origins", () -> ALLOWED_ORIGIN);
        registry.add("nexachat.security.admin-phones", () -> ADMIN_PHONE);
        registry.add("nexachat.storage.root", SecurityITBase::tempStorageRoot);
    }

    private static String tempStorageRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("nexachat-sec-it-").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create the IT storage root", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected JwtProperties jwtProperties;
    @Autowired
    private RateLimitService rateLimitService;

    /**
     * The test database is shared with the other integration suites, so each
     * test truncates the user-owned data first. {@code TRUNCATE users CASCADE}
     * walks every foreign key that points at a user (conversations, messages,
     * attachments, tokens, reactions, pins, summaries) in one statement.
     *
     * <p>The in-memory rate-limit buckets live in the shared singleton bean, so
     * they are cleared too: every test then starts from the same empty state
     * instead of inheriting a previous test's budget.
     */
    @BeforeEach
    void resetUsers() {
        truncateUsersCascade();
        rateLimitService.reset();
    }

    /**
     * Clears the shared tables with a bounded lock wait and a retry.
     *
     * <p>A plain {@code TRUNCATE users CASCADE} waits without a bound. Cached
     * Spring contexts keep their scheduled jobs alive for the whole test JVM,
     * and an earlier method can leave a worker finishing a transaction after
     * its test returned. Either session can hold a relation lock that the
     * cascade needs; when it then requests a table the truncate already
     * locked, PostgreSQL reports {@code deadlock detected} (or the truncate
     * waits forever, with no assertion in control). Bounding each attempt
     * turns that into a retryable lock timeout; the truncate is idempotent, so
     * retrying cannot change any assertion result.
     */
    private void truncateUsersCascade() {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET lock_timeout = " + RESET_LOCK_TIMEOUT_MS);
                        try {
                            statement.execute("TRUNCATE users CASCADE");
                        } finally {
                            statement.execute("SET lock_timeout = 0");
                        }
                    }
                    return null;
                });
                return;
            } catch (DataAccessException failure) {
                if (attempt >= RESET_MAX_ATTEMPTS || !isTransientLockFailure(failure)) {
                    throw failure;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100L * attempt));
            }
        }
    }

    private static boolean isTransientLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if ("55P03".equals(state) || "40P01".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return postJson(path, body, null);
    }

    protected ResultActions postJson(String path, Object body, String accessToken) throws Exception {
        return postJson(path, body, accessToken, request -> request);
    }

    protected ResultActions postJson(String path, Object body, String accessToken,
                                     RequestPostProcessor postProcessor) throws Exception {
        var builder = post(path)
                .with(postProcessor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (accessToken != null) {
            builder.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    protected ResultActions postRaw(String path, String json, String accessToken,
                                    RequestPostProcessor postProcessor) throws Exception {
        var builder = post(path)
                .with(postProcessor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (accessToken != null) {
            builder.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    protected ResultActions getWithAuth(String path, String accessToken) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path).header("Authorization", "Bearer " + accessToken));
    }

    protected ResultActions patchWithAuth(String path, Object body, String accessToken) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Registers a fresh account and returns its tokens. */
    protected Registered register(String phone) throws Exception {
        return register(phone, "secret123");
    }

    protected Registered register(String phone, String password) throws Exception {
        var result = postJson("/api/v1/auth/register", Map.of(
                        "phone", phone, "password", password, "displayName", "Sec User"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    /** Authenticates an existing account and returns the new session tokens. */
    protected Registered login(String phone) throws Exception {
        var result = postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "secret123"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    /**
     * A signed access token for {@code userId} stamped five seconds before now.
     *
     * <p>The access-token cutoff is stored at one-second resolution (JWT
     * {@code iat} has no sub-second precision), so tokens at or before the
     * cutoff second fail closed. Stamping the token explicitly makes
     * "issued before the cutoff" deterministic without sleeping across a
     * second boundary.
     */
    protected String accessTokenIssuedBeforeRevocation(UUID userId) {
        return accessTokenIssuedBeforeRevocation(userId, 5);
    }

    protected String accessTokenIssuedBeforeRevocation(UUID userId, long secondsAgo) {
        return TestAccessTokens.accessTokenIssuedAt(
                jwtProperties, userId, Instant.now().minusSeconds(secondsAgo));
    }

    /** Sets the client IP the rate limiter sees. */
    protected static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    protected record Registered(UUID userId, String accessToken, String refreshToken) {
    }
}
