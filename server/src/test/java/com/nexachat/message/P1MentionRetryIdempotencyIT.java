package com.nexachat.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retrying an identical idempotent send after a mentioned member was removed
 * must return the stored message instead of re-validating mentions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1MentionRetryIdempotencyIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void setUp() {
        alice = createTestUser("Retry Alice");
        bob = createTestUser("Retry Bob");
    }

    @Test
    void retryAfterMentionTargetRemovalStillReturnsTheStoredMessage() throws Exception {
        UUID groupId = createGroup(alice, List.of(bob.id()));
        UUID clientMessageId = UUID.randomUUID();
        String content = "hello @Bob hi";
        Map<String, Object> send = Map.of(
                "clientMessageId", clientMessageId.toString(),
                "type", "TEXT",
                "content", content,
                "mentions", List.of(Map.of(
                        "userId", bob.id().toString(),
                        "offset", 6,
                        "length", 4)));

        MvcResult first = postJson("/api/v1/conversations/" + groupId + "/messages",
                alice.accessToken(), send)
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(objectMapper.readTree(
                first.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/v1/conversations/{c}/members/{u}", groupId, bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        MvcResult retry = postJson("/api/v1/conversations/" + groupId + "/messages",
                alice.accessToken(), send)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString())
                .get("id").asText()).isEqualTo(messageId.toString());
    }

    private TestUser createTestUser(String displayName) {
        String phone = "+8495" + String.format("%07d",
                ThreadLocalRandom.current().nextInt(10_000_000));
        User user = User.create(phone, "hash", displayName);
        user = userRepository.saveAndFlush(user);
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private UUID createGroup(TestUser creator, List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", "Mention retry guard", "memberIds", ids))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(
            String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }
}
