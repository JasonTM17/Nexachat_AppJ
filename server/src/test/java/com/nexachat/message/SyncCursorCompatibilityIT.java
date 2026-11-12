package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves legacy requests and switch-off negotiation retain the v1 sync envelope. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncCursorCompatibilityIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("nexachat.sync.change-cursors-enabled", () -> "false");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private UUID conversationId;

    @BeforeAll
    void fixture() throws Exception {
        String tag = "SYNCCOMPAT" + System.nanoTime();
        alice = register(tag + " Alice");
        TestUser bob = register(tag + " Bob");
        JsonNode conversation = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "sync-compat-" + System.nanoTime(),
                                "memberIds", List.of(bob.id().toString())))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        conversationId = UUID.fromString(conversation.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void v1AndV2FallbackRequestsReturnOnlyLegacyDeltaFields() throws Exception {
        JsonNode v1 = sync(false);
        JsonNode v2Fallback = sync(true);

        assertLegacyShape(v1);
        assertLegacyShape(v2Fallback);
        assertThat(v1.get("deltas").get(0).get("newMessages").size()).isGreaterThan(0);
        assertThat(v2Fallback.get("deltas").get(0).get("newMessages").size()).isGreaterThan(0);

        JsonNode message = v1.get("deltas").get(0).get("newMessages").get(0);
        assertThat(message.has("id")).isTrue();
        assertThat(message.has("seq")).isTrue();
        assertThat(message.has("content")).isTrue();
        // These are additive row metadata; older clients can ignore them.
        assertThat(message.has("changeVersion")).isTrue();
        assertThat(message.has("generation")).isTrue();
    }

    private JsonNode sync(boolean negotiateV2) throws Exception {
        Map<String, Object> watermark = new java.util.LinkedHashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", 0L);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("watermarks", List.of(watermark));
        body.put("limitPerConv", 20);
        if (negotiateV2) {
            body.put("syncVersion", 2);
            watermark.put("changeCursor", 0L);
        }
        MockHttpServletRequestBuilder request = post("/api/v1/sync")
                .header("Authorization", bearer(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static void assertLegacyShape(JsonNode response) {
        assertThat(response.has("deltas")).isTrue();
        assertThat(response.has("serverTime")).isTrue();
        assertThat(response.has("syncVersion")).isFalse();
        JsonNode delta = response.get("deltas").get(0);
        assertThat(delta.has("conversationId")).isTrue();
        assertThat(delta.has("newMessages")).isTrue();
        assertThat(delta.has("editedMessages")).isTrue();
        assertThat(delta.has("deletedMessageIds")).isTrue();
        assertThat(delta.has("reactionUpdates")).isTrue();
        assertThat(delta.has("maxSeq")).isTrue();
        assertThat(delta.has("hasMore")).isTrue();
        assertThat(delta.has("reactionsFullRefresh")).isTrue();
        assertThat(delta.has("muted")).isTrue();
        assertThat(delta.has("archived")).isTrue();
        assertThat(delta.has("pinned")).isTrue();
        assertThat(delta.has("changes")).isFalse();
        assertThat(delta.has("changeCursor")).isFalse();
        assertThat(delta.has("changeHasMore")).isFalse();
        assertThat(delta.has("changeUpperBound")).isFalse();
        assertThat(delta.has("reset")).isFalse();
        assertThat(delta.has("generation")).isFalse();
    }

    private TestUser register(String displayName) throws Exception {
        String phone = "+8496" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return new TestUser(UUID.fromString(response.get("user").get("id").asText()),
                response.get("accessToken").asText());
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
