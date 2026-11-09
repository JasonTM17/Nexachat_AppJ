package com.nexachat.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.llm.LlmSummaryProvider;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class SummaryOptOutIT {

    private static final FakeOpenAiServer LLM = startFakeServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("nexachat.summary.llm.enabled", () -> true);
        registry.add("nexachat.summary.llm.api-key", () -> "test-only-key");
        registry.add("nexachat.summary.llm.base-url", LLM::baseUrl);
        registry.add("nexachat.summary.llm.model", () -> "test-model");
        registry.add("nexachat.summary.llm.timeout-ms", () -> 5_000);
        registry.add("nexachat.summary.complexity.min-messages", () -> 1);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static FakeOpenAiServer startFakeServer() {
        try {
            return new FakeOpenAiServer();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void closeFakeServer() {
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
    @Autowired
    private SummaryWindowService windowService;
    @Autowired
    private LlmSummaryProvider llmSummaryProvider;
    @Autowired
    private ConversationAiOptOuts aiOptOuts;

    private User caller;
    private User peer;
    private User outsider;
    private UUID conversationId;

    @BeforeEach
    void createFixture() {
        SecurityContextHolder.clearContext();
        LLM.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validJson())));
        caller = createUser("Summary opt-out caller");
        peer = createUser("Summary opt-out peer");
        outsider = createUser("Summary opt-out outsider");
        Conversation conversation = conversationRepository.save(Conversation.group(caller, "Summary opt-out fixture"));
        conversationId = conversation.getId();
        addMember(caller.getId());
        addMember(peer.getId());
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
    }

    @AfterEach
    void removeFixture() {
        SecurityContextHolder.clearContext();
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID userId : List.of(caller.getId(), peer.getId(), outsider.getId())) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void externalSummaryOmitsOptedOutMessagesAndDropsTheStaleCacheEntry() throws Exception {
        TestMessage peerFact = addMessage(peer.getId(),
                "The private migration date is alpha-bravo March 14.");
        addMessage(caller.getId(), "The team reviewed the release notes.");
        addMessage(caller.getId(), "The demo is scheduled for next week.");
        addMessage(caller.getId(), "The review is complete.");
        addMessage(caller.getId(), "The rollout starts after the review.");
        int requestsBefore = LLM.requestCount();

        MvcResult first = postSummaryRefresh(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy").value("LLM"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.contextOmitted").value(false))
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(citations(firstBody)).contains(peerFact.id().toString());
        assertThat(LLM.requests().get(requestsBefore).body()).contains("alpha-bravo");

        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/ai-opt-out")
                        .header("Authorization", bearer(peer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiOptOut").value(true));

        MvcResult second = postSummaryRefresh(false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy").value("LLM"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.contextOmitted").value(true))
                .andReturn();
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(citations(secondBody)).doesNotContain(peerFact.id().toString());
        assertThat(LLM.requests().get(LLM.requestCount() - 1).body()).doesNotContain("alpha-bravo");
        assertThat(LLM.requestCount()).isEqualTo(requestsBefore + 2);
    }

    @Test
    void rechecksOptOutWhenTheWindowWasLoadedBeforeTheCall() throws Exception {
        TestMessage peerFact = addMessage(peer.getId(),
                "The private migration date is alpha-bravo March 14.");
        addMessage(caller.getId(), "The team reviewed the release notes.");
        addMessage(caller.getId(), "The demo is scheduled for next week.");
        addMessage(caller.getId(), "The review is complete.");
        addMessage(caller.getId(), "The rollout starts after the review.");

        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, true)).window();
        assertThat(window.messages()).hasSize(5);
        assertThat(window.messages()).extracting(SummaryMessage::id).contains(peerFact.id());

        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/ai-opt-out")
                        .header("Authorization", bearer(peer)))
                .andExpect(status().isOk());
        assertThat(aiOptOuts.optedOutUserIds(conversationId)).containsExactly(peer.getId());
        assertThat(window.messages()).extracting(SummaryMessage::senderId)
                .containsOnly(caller.getId(), peer.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(caller.getId()), null, List.of()));
        int requestsBefore = LLM.requestCount();

        ProviderResult result = llmSummaryProvider.summarize(window, SummaryLanguage.EN);

        assertThat(LLM.requests().get(requestsBefore).body()).doesNotContain("alpha-bravo");
        assertThat(result.content().keyPoints()).isNotEmpty();
        assertThat(result.content().keyPoints().get(0).messageIds()).doesNotContain(peerFact.id());
    }

    @Test
    void revokedConsentKeepsTheSummaryLocalAndMakesNoExternalCall() throws Exception {
        addMessage(peer.getId(), "The team agreed on the plan.");
        addMessage(caller.getId(), "The release notes are ready.");
        addMessage(caller.getId(), "The demo is scheduled.");
        addMessage(caller.getId(), "The review is complete.");
        addMessage(caller.getId(), "The next step is the rollout.");
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE WHERE id = ?", caller.getId());
        int requestsBefore = LLM.requestCount();

        postSummaryRefresh(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy").value("LOCAL"))
                .andExpect(jsonPath("$.fallbackReason").value("NO_CONSENT"));

        assertThat(LLM.requestCount()).isEqualTo(requestsBefore);
    }

    private org.springframework.test.web.servlet.ResultActions postSummaryRefresh(boolean refresh) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "scope", "UNREAD", "language", "EN", "refresh", refresh));
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                .header("Authorization", bearer(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private List<String> citations(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (String section : List.of("keyPoints", "decisions", "actionItems", "questions")) {
            for (JsonNode item : body.path(section)) {
                for (JsonNode messageId : item.path("messageIds")) {
                    ids.add(messageId.asText());
                }
            }
        }
        return ids;
    }

    private static String validJson() {
        return "{\"overview\":\"The group agreed on the next steps.\","
                + "\"keyPoints\":[{\"text\":\"The team agreed.\",\"citations\":[1]}],"
                + "\"decisions\":[],\"actionItems\":[],\"questions\":[],\"topics\":[\"Next steps\"]}";
    }

    private User createUser(String displayName) {
        String phone = "+849" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L));
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }

    private void addMember(UUID userId) {
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id, last_read_seq)"
                + " VALUES (?, ?, 0)", conversationId, userId);
    }

    private TestMessage addMessage(UUID senderId, String content) {
        UUID messageId = UUID.randomUUID();
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO messages
                    (id, conversation_id, sender_id, client_message_id, type, content, created_at)
                VALUES (?, ?, ?, ?, 'TEXT', CAST(? AS text), ?)
                RETURNING seq
                """, Long.class, messageId, conversationId, senderId, UUID.randomUUID(),
                content, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        return new TestMessage(messageId, sequence);
    }

    private record TestMessage(UUID id, long seq) {
    }
}
