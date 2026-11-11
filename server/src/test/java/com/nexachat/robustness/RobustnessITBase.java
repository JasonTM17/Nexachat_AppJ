package com.nexachat.robustness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared database, storage, and MockMvc wiring for the API input-robustness suite.
 *
 * <p>Each subclass owns the <em>whole</em> shared IT database while it runs:
 * {@link #resetSharedFixtureDatabase()} truncates {@code users} before the
 * class fixture is seeded, so the suites must run sequentially (the Failsafe
 * default). Parallel test execution would delete another class's fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
abstract class RobustnessITBase {

    @DynamicPropertySource
    static void robustnessItProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("nexachat.storage.root", RobustnessITBase::tempStorageRoot);
    }

    private static String tempStorageRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("nexachat-robustness-it-").toString();
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
    private RateLimitService rateLimitService;

    @BeforeEach
    void resetRateLimits() {
        rateLimitService.reset();
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

    protected ResultActions postRaw(String path, String json, String accessToken) throws Exception {
        var builder = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (accessToken != null) {
            builder.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    protected Registered register(String phone, String displayName) throws Exception {
        var result = postJson("/api/v1/auth/register", Map.of(
                "phone", phone,
                "password", "secret123",
                "displayName", displayName), null, remoteAddr(uniqueIp()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    protected DirectConversation openDirect(Registered caller, UUID peerUserId) throws Exception {
        var result = postJson("/api/v1/conversations/direct",
                Map.of("userId", peerUserId.toString()), caller.accessToken())
                .andReturn();
        UUID conversationId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        return new DirectConversation(conversationId);
    }

    protected UUID sendText(Registered sender, UUID conversationId, String text) throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        var result = postJson("/api/v1/conversations/" + conversationId + "/messages",
                Map.of(
                        "clientMessageId", clientMessageId.toString(),
                        "type", "TEXT",
                        "content", text),
                sender.accessToken())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /**
     * Clears the shared fixture tables before a class-scoped fixture is seeded.
     *
     * <p>This is only safe under sequential execution: every class in this
     * package truncates the same database, so a parallel class would lose its
     * users mid-run. JUnit's parallel switch is therefore a hard failure here
     * instead of a silent, order-dependent corruption.
     */
    protected void resetSharedFixtureDatabase() {
        String parallel = System.getProperty("junit.jupiter.execution.parallel.enabled", "false");
        if (!"false".equalsIgnoreCase(parallel)) {
            throw new IllegalStateException(
                    "The robustness IT suites truncate the shared IT database and must run sequentially");
        }
        jdbcTemplate.update("TRUNCATE users CASCADE");
    }

    protected long messageCount(UUID conversationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
        return count == null ? 0L : count;
    }

    private static int ipCounter;

    protected static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    protected static String uniqueIp() {
        return "10.40.0." + (++ipCounter % 200 + 1);
    }

    protected record Registered(UUID userId, String accessToken, String refreshToken) {
    }

    protected record DirectConversation(UUID id) {
    }
}
