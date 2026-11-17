package com.nexachat.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for the streak and public-feed integration suites: one Spring
 * context against the disposable PostgreSQL database, an empty user table
 * before every test, and thin HTTP helpers.
 */
@SpringBootTest(properties = "nexachat.e2ee.v1-activation-enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
public abstract class FeatureItSupport {

    @DynamicPropertySource
    static void featureItProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("nexachat.storage.root", FeatureItSupport::tempStorageRoot);
    }

    private static String tempStorageRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("nexachat-feature-it-").toString();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not create the IT storage root", failure);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** A signed-in test user. */
    public record Actor(UUID id, String token, String name) {
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void resetSharedState() {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET lock_timeout = 5000");
                        try {
                            statement.execute("TRUNCATE users CASCADE");
                        } finally {
                            statement.execute("SET lock_timeout = 0");
                        }
                    }
                    return null;
                });
                break;
            } catch (DataAccessException failure) {
                if (attempt >= 4 || !isLockFailure(failure)) {
                    throw failure;
                }
            }
        }
        rateLimitService.reset();
    }

    private static boolean isLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ users

    protected Actor newUser(String name) {
        User user = userRepository.saveAndFlush(
                User.create(ItPhoneRanges.nextPhone(getClass()), "x".repeat(60), name));
        return new Actor(user.getId(), jwtService.generateAccessToken(user), name);
    }

    /** Re-issues the user's token after a role change (the filter reads the role fresh anyway). */
    protected Actor withRole(Actor actor, String role) {
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, actor.id());
        return actor;
    }

    // ------------------------------------------------------------ conversations

    protected UUID openDirect(Actor a, Actor b) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header(HttpHeaders.AUTHORIZATION, bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", b.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    protected UUID createGroup(Actor owner, String title, Actor... members) throws Exception {
        List<String> ids = java.util.Arrays.stream(members).map(m -> m.id().toString()).toList();
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/group")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "memberIds", ids))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    protected ResultActions sendText(UUID conversationId, Actor sender, UUID clientMessageId, String content)
            throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, bearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "clientMessageId", clientMessageId.toString(),
                        "type", "TEXT", "content", content))));
    }

    protected ResultActions sendText(UUID conversationId, Actor sender, String content) throws Exception {
        return sendText(conversationId, sender, UUID.randomUUID(), content);
    }

    // ------------------------------------------------------------------- http

    protected static String bearer(Actor actor) {
        return "Bearer " + actor.token();
    }

    protected ResultActions getAs(Actor actor, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    protected ResultActions postAs(Actor actor, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(actor));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    protected ResultActions putAs(Actor actor, String path) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    protected ResultActions patchAs(Actor actor, String path, Object body) throws Exception {
        return mockMvc.perform(patch(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions deleteAs(Actor actor, String path) throws Exception {
        return mockMvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode json(ResultActions actions) throws Exception {
        return json(actions.andReturn());
    }

    protected int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
