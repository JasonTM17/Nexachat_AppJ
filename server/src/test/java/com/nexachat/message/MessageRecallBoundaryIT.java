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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recall window boundaries proven via wall-clock shifted rows (default 24h). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageRecallBoundaryIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "ITR" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
    }

    @Test
    void recallSucceedsOnTheInclusiveBoundaryAndFailsImmediatelyAfter() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID messageId = send(conversationId, alice, "boundary recall");

        jdbcTemplate.update(
                "UPDATE messages SET created_at = now() - interval '24 hours' + interval '1 second' WHERE id = ?",
                messageId);
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        UUID secondId = send(conversationId, alice, "after first recall");
        jdbcTemplate.update(
                "UPDATE messages SET created_at = now() - interval '24 hours' - interval '1 second' WHERE id = ?",
                secondId);
        MvcResult denied = mockMvc.perform(delete(
                        "/api/v1/conversations/{c}/messages/{m}", conversationId, secondId)
                .header("Authorization", bearer(alice)))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode error = objectMapper.readTree(denied.getResponse().getContentAsString());
        assertThat(error.path("error").path("code").asText()).isEqualTo("RECALL_WINDOW_EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM messages WHERE id = ?", Boolean.class, secondId)).isTrue();
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8490" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID openDirect(TestUser first, TestUser second) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", second.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private UUID send(UUID conversationId, TestUser sender, String content) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "content", content))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
