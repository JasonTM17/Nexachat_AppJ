package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the auth slice against a real PostgreSQL. The
 * {@code it} profile keeps every autoconfiguration — Flyway applies
 * V1__init.sql on boot and the datasource comes from env vars with defaults
 * pointing at the local docker-compose instance ({@code localhost:5433});
 * CI overrides them to its postgres service. (Testcontainers was the first
 * choice, but docker-java cannot negotiate with this workstation's
 * Docker Desktop 29 named pipes; JDBC keeps the same coverage.)
 *
 * <p>Covers: register/login/refresh happy path, rotation + reuse detection
 * (family revocation asserted directly on {@code refresh_tokens}),
 * anti-enumeration 401s, the Bearer guard on protected endpoints, and bean
 * validation — every error asserted in the {@code {"error":{"code","message"}}}
 * envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class AuthFlowIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("nexachat.message.recall-window", () -> "PT48H");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;

    /**
     * The IT runs against a SHARED dev database (docker-compose :5433), so
     * previous runs leave their users behind — unlike a hermetic container.
     * Wipe only the IT phone ranges, in FK-safe order: a user's conversations
     * first (no cascade on {@code created_by}), then the users themselves.
     */
    @BeforeEach
    void cleanItUsers() {
        ItPhoneRanges.clean(jdbcTemplate, AuthFlowIT.class);
    }

    @Test
    void registerLoginRefreshReuse() throws Exception {
        String phone = "+84815001122";

        // 1. Register → 201 with the full token payload.
        MvcResult registered = postJson("/api/v1/auth/register",
                Map.of("phone", phone, "password", "secret123", "displayName", "IT User"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.accessTokenExpiresIn").isNumber())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.recallWindowSeconds").value(172800))
                .andExpect(jsonPath("$.groupAdminMayRecallAfterWindow").value(true))
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.phone").value(phone))
                .andExpect(jsonPath("$.user.displayName").value("IT User"))
                .andReturn();
        JsonNode registeredBody = objectMapper.readTree(registered.getResponse().getContentAsString());
        String rt1 = registeredBody.get("refreshToken").asText();
        UUID userId = UUID.fromString(registeredBody.get("user").get("id").asText());

        // 2. Login with the same credentials → 200 (issues a second live token).
        postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "secret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.recallWindowSeconds").value(172800))
                .andExpect(jsonPath("$.groupAdminMayRecallAfterWindow").value(true))
                .andExpect(jsonPath("$.user.id").value(userId.toString()));

        // 3. Rotate rt1 → 200 and a NEW refresh token (rt2 != rt1).
        MvcResult refreshed = postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.recallWindowSeconds").value(172800))
                .andExpect(jsonPath("$.groupAdminMayRecallAfterWindow").value(true))
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        String rt2 = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rt2).isNotEqualTo(rt1);

        // 4. Replay the consumed rt1 → reuse detection: 401 TOKEN_REUSED.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"))
                .andExpect(jsonPath("$.error.message").isString());

        // 5. Reuse revoked the WHOLE family, committed in its own transaction:
        //    register-token + login-token + rotated rt2 all carry revoked_at.
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        Integer liveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(totalRows).isGreaterThanOrEqualTo(3);
        assertThat(liveRows).isZero();
    }

    @Test
    void concurrentRefreshRotationAllowsOneWinnerAndRevokesTheFamily() throws Exception {
        String phone = "+84815007788";
        MvcResult registered = postJson("/api/v1/auth/register",
                Map.of("phone", phone, "password", "secret123", "displayName", "Race User"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString());
        String refreshToken = body.get("refreshToken").asText();
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        String requestBody = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)).andReturn();
            });
            Future<MvcResult> second = executor.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)).andReturn();
            });

            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            MvcResult rejected = firstResult.getResponse().getStatus() == 401 ? firstResult : secondResult;
            assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("TOKEN_REUSED");

            Integer liveRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                    Integer.class, userId);
            assertThat(liveRows).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrongLoginIs401Not404() throws Exception {
        // Unknown phone → 401 INVALID_CREDENTIALS (never 404 — anti-enumeration).
        postJson("/api/v1/auth/login", Map.of("phone", "+84815003344", "password", "secret123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").isString());

        // Known phone + wrong password → the SAME code/status.
        String phone = "+84815003355";
        postJson("/api/v1/auth/register",
                Map.of("phone", phone, "password", "secret123", "displayName", "Known User"))
                .andExpect(status().isCreated());
        postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "wrongpass9"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void duplicatePhoneIs409() throws Exception {
        Map<String, String> body =
                Map.of("phone", "+84815004466", "password", "secret123", "displayName", "Dup User");

        postJson("/api/v1/auth/register", body).andExpect(status().isCreated());
        postJson("/api/v1/auth/register", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PHONE_EXISTS"))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void protectedEndpointRequiresBearer() throws Exception {
        // No Authorization header → 401 UNAUTHORIZED.
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").isString());

        // Garbage Bearer → 401 TOKEN_INVALID (code stashed by JwtAuthFilter).
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void accessTokenForDeletedUserIsRejected() throws Exception {
        User user = userRepository.saveAndFlush(
                User.create("+84815008881", "x".repeat(60), "Deleted User"));
        String accessToken = jwtService.generateAccessToken(user);
        userRepository.deleteById(user.getId());
        userRepository.flush();

        mockMvc.perform(get("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void registerValidationErrors() throws Exception {
        // Blank phone + 5-char password → 400 VALIDATION_FAILED with field details.
        postJson("/api/v1/auth/register",
                Map.of("phone", "", "password", "short", "displayName", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details.phone").exists())
                .andExpect(jsonPath("$.error.details.password").exists());
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
