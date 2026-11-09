package com.nexachat.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.rag.JdbcRagMessageIndex;
import com.nexachat.rag.RagMessage;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class ConversationAiOptOutIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
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
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcRagMessageIndex index;

    private User caller;
    private User peer;
    private User outsider;
    private UUID conversationId;

    @BeforeEach
    void createFixture() {
        caller = createUser("Opt-out caller");
        peer = createUser("Opt-out peer");
        outsider = createUser("Opt-out outsider");
        Conversation conversation = conversationRepository.save(Conversation.group(caller, "Opt-out fixture"));
        conversationId = conversation.getId();
        addMember(caller.getId());
        addMember(peer.getId());
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (UUID userId : List.of(caller.getId(), peer.getId(), outsider.getId())) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void optOutEndpointsAreMemberOnlyAndIdempotent() throws Exception {
        putOptOut(outsider)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        mockMvc.perform(put("/api/v1/conversations/" + UUID.randomUUID() + "/ai-opt-out")
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));

        putOptOut(caller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.aiOptOut").value(true));
        getOptOut(caller).andExpect(status().isOk()).andExpect(jsonPath("$.aiOptOut").value(true));
        putOptOut(caller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiOptOut").value(true));
        assertThat(optOutRows()).isEqualTo(1);

        clearOptOut(caller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiOptOut").value(false));
        getOptOut(caller).andExpect(status().isOk()).andExpect(jsonPath("$.aiOptOut").value(false));
        clearOptOut(caller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiOptOut").value(false));
        assertThat(optOutRows()).isZero();
    }

    @Test
    void retrievalCacheAndIndexStopUsingAnOptedOutAuthorsMessages() throws Exception {
        TestMessage fact = addMessage(peer.getId(),
                "The server migration is scheduled for March 14 at 02:00.");
        addMessage(caller.getId(), "We discussed the release notes.");

        postAsk(caller, "{\"question\":\"When is the server migration scheduled?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("EXTRACTIVE"))
                .andExpect(jsonPath("$.citations[0].messageId").value(fact.id().toString()));
        assertThat(embeddedRows(fact.id())).isEqualTo(1);
        assertThat(askCacheRows()).isEqualTo(1);

        putOptOut(peer).andExpect(status().isOk());

        assertThat(embeddedRows(fact.id())).isZero();
        assertThat(askCacheRows()).isZero();

        MvcResult result = postAsk(caller, "{\"question\":\"When is the server migration scheduled?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.contextOmitted").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("March 14");
    }

    @Test
    void indexSelectionSkipsOptedOutAuthorsAndRestoresOnClear() throws Exception {
        TestMessage message = addMessage(peer.getId(), "The migration is on March 14.");
        putOptOut(peer).andExpect(status().isOk());

        assertThat(unindexedIds()).doesNotContain(message.id());
        assertThat(index.recentMessages(conversationId, 100))
                .extracting(RagMessage::id).doesNotContain(message.id());
        assertThat(index.findMessage(conversationId, message.id())).isNull();

        clearOptOut(peer).andExpect(status().isOk());

        assertThat(unindexedIds()).contains(message.id());
        assertThat(index.recentMessages(conversationId, 100))
                .extracting(RagMessage::id).contains(message.id());
    }

    @Test
    void inboxExposesOnlyTheCallersOwnAiOptOutState() throws Exception {
        putOptOut(peer).andExpect(status().isOk());

        assertThat(entryFor(inbox(caller), conversationId).path("aiOptOut").asBoolean())
                .as("the caller has not opted out")
                .isFalse();
        assertThat(entryFor(inbox(peer), conversationId).path("aiOptOut").asBoolean())
                .as("each member sees their own state")
                .isTrue();
    }

    @Test
    void directConversationResponseCarriesTheCallersOptOutState() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + peer.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aiOptOut").value(false))
                .andReturn();
        UUID directId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()).path("id").asText());
        try {
            mockMvc.perform(put("/api/v1/conversations/" + directId + "/ai-opt-out")
                            .header("Authorization", bearer(caller)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/conversations/direct")
                            .header("Authorization", bearer(caller))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + peer.getId() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.aiOptOut").value(true));
        } finally {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", directId);
        }
    }

    private JsonNode inbox(User user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode entryFor(JsonNode inbox, UUID conversationId) {
        for (JsonNode entry : inbox) {
            if (conversationId.toString().equals(entry.path("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Conversation is missing from the inbox");
    }

    private ResultActions putOptOut(User user) throws Exception {
        return mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/ai-opt-out")
                .header("Authorization", bearer(user)));
    }

    private ResultActions clearOptOut(User user) throws Exception {
        return mockMvc.perform(delete("/api/v1/conversations/" + conversationId + "/ai-opt-out")
                .header("Authorization", bearer(user)));
    }

    private ResultActions getOptOut(User user) throws Exception {
        return mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/ai-opt-out")
                .header("Authorization", bearer(user)));
    }

    private ResultActions postAsk(User user, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ask")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private int optOutRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_ai_opt_outs WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private int embeddedRows(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_embeddings WHERE message_id = ?",
                Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int askCacheRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ask_cache WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private Set<UUID> unindexedIds() {
        return index.findUnindexed("lexical", 100).stream()
                .map(RagMessage::id)
                .collect(java.util.stream.Collectors.toSet());
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
