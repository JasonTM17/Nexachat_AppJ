package com.nexachat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recalling a message must purge a cached ask answer that used the recalled
 * text as uncited prompt context.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class P1AskRecallUncitedCacheIT {

    private static final FakeOpenAiServer LLM = startServer();
    private static final AtomicInteger LLM_CALLS = new AtomicInteger();
    private static final Pattern SAFE_PROMPT_ITEM = Pattern.compile(
            "\"i\":(\\d+),\"sender\":\"[^\"]+\",\"text\":\"[^\"]*budget[^\"]*\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("nexachat.summary.llm.enabled", () -> true);
        registry.add("nexachat.summary.llm.api-key", () -> "test-only-key");
        registry.add("nexachat.summary.llm.base-url", LLM::baseUrl);
        registry.add("nexachat.summary.llm.model", () -> "test-model");
        registry.add("nexachat.summary.llm.timeout-ms", () -> 5_000);
    }

    @AfterAll
    static void closeServer() {
        LLM.close();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private JwtService jwtService;

    private User caller;
    private User peer;
    private UUID conversationId;
    private UUID secretMessageId;

    @BeforeEach
    void createFixture() {
        LLM_CALLS.set(0);
        LLM.handle((exchange, body, authorization) -> {
            int call = LLM_CALLS.getAndIncrement();
            int safeIndex = safePromptIndex(body);
            String answer = call == 0
                    ? "The secret value is alpha-bravo."
                    : "The budget was approved.";
            String content = "{\"answer\":" + FakeOpenAiServer.quote(answer)
                    + ",\"citations\":[" + safeIndex + "]}";
            FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse(content));
        });

        caller = createUser("Ask recall caller");
        peer = createUser("Ask recall peer");
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
        Conversation conversation = conversationRepository.save(
                Conversation.group(caller, "Ask recall cache fixture"));
        conversationId = conversation.getId();
        addMember(caller.getId());
        addMember(peer.getId());
        addMessage(caller.getId(), "The budget was approved for the release plan.");
        secretMessageId = addMessage(peer.getId(), "The secret value is alpha-bravo.");
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID userId : List.of(caller.getId(), peer.getId())) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void recallMustInvalidateACachedAnswerThatUsedTheMessageAsContext() throws Exception {
        String question = "Please tell me the secret alpha bravo value and the budget approval details "
                + "from the team discussion, including the release plan, the review status, the rollout "
                + "timing and any other relevant project milestones";
        String body = objectMapper.writeValueAsString(java.util.Map.of("question", question));

        MvcResult first = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ask")
                        .header("Authorization", bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.path("mode").asText()).isEqualTo("LLM");
        assertThat(firstBody.path("answer").asText()).contains("alpha-bravo");

        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, secretMessageId)
                        .header("Authorization", bearer(peer)))
                .andExpect(status().isNoContent());

        MvcResult second = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ask")
                        .header("Authorization", bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String secondRaw = second.getResponse().getContentAsString();
        JsonNode secondBody = objectMapper.readTree(secondRaw);

        assertThat(secondRaw)
                .as("a recalled message must not come back through a previously cached answer")
                .doesNotContain("alpha-bravo");
        assertThat(secondBody.path("mode").asText())
                .as("the stale cache row must not be replayed")
                .isNotEqualTo("CACHE");
    }

    private static int safePromptIndex(String requestBody) {
        Matcher matcher = SAFE_PROMPT_ITEM.matcher(requestBody);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1;
    }

    private static FakeOpenAiServer startServer() {
        try {
            return new FakeOpenAiServer();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private User createUser(String displayName) {
        String phone = "+848" + String.format("%09d",
                Math.floorMod(System.nanoTime(), 1_000_000_000L));
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }

    private void addMember(UUID userId) {
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id, last_read_seq)"
                + " VALUES (?, ?, 0)", conversationId, userId);
    }

    private UUID addMessage(UUID senderId, String content) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages
                    (id, conversation_id, sender_id, client_message_id, type, content, created_at)
                VALUES (?, ?, ?, ?, 'TEXT', CAST(? AS text), ?)
                """, id, conversationId, senderId, UUID.randomUUID(), content,
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        return id;
    }
}
