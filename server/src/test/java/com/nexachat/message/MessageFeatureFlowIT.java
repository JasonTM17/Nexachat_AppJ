package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageFeatureFlowIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MessageService messageService;

    private record TestUser(UUID id, String accessToken) { }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "ITM" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    @BeforeEach
    void clearPreviousTestConversations() {
        jdbcTemplate.update("DELETE FROM conversations WHERE created_by = ?", alice.id());
    }

    @Test
    void editDeleteFlowEnforcesAuthorAndMemberAndPreservesTombstoneHistory() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID otherConversationId = openDirect(alice, carol);
        UUID messageId = send(conversationId, alice, "original text");
        UUID replyId = send(conversationId, bob, "reply to original", messageId);

        assertError("NOT_MESSAGE_AUTHOR", 403, patchMessage(conversationId, messageId, bob, "no"));
        assertError("NOT_MEMBER", 403, patchMessage(conversationId, messageId, carol, "no"));
        assertError("MESSAGE_NOT_FOUND", 404, patchMessage(otherConversationId, messageId, alice, "no"));
        assertError("MESSAGE_NOT_FOUND", 404,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", otherConversationId, messageId)
                        .header("Authorization", bearer(alice))));

        JsonNode edited = objectMapper.readTree(patchMessage(conversationId, messageId, alice, "  edited text  ")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(edited.get("content").asText()).isEqualTo("edited text");
        assertThat(edited.get("editedAt").isTextual()).isTrue();
        String editedAt = edited.get("editedAt").asText();

        JsonNode unchanged = objectMapper.readTree(patchMessage(conversationId, messageId, alice, "edited text")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(unchanged.get("editedAt").asText()).isEqualTo(editedAt);

        mockMvc.perform(post("/api/v1/messages/{id}/reactions", messageId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"👍\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/conversations/{id}/pins", conversationId)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("messageId", messageId.toString()))))
                .andExpect(status().isNoContent());

        assertError("NOT_MESSAGE_AUTHOR", 403,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(bob))));
        assertError("NOT_MEMBER", 403,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(carol))));

        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        assertError("MESSAGE_DELETED", 409, patchMessage(conversationId, messageId, alice, "late edit"));

        JsonNode history = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode tombstone = findById(history.get("items"), messageId);
        assertThat(tombstone.get("seq").asLong()).isPositive();
        assertThat(tombstone.has("content")).isTrue();
        assertThat(tombstone.get("content").isNull()).isTrue();
        assertThat(tombstone.get("deletedAt").isTextual()).isTrue();
        assertThat(tombstone.get("attachments")).isEmpty();
        assertThat(tombstone.get("reactions")).isEmpty();
        JsonNode reply = findById(history.get("items"), replyId);
        assertThat(reply.get("replyTo").has("content")).isTrue();
        assertThat(reply.get("replyTo").get("content").isNull()).isTrue();
        assertThat(reply.get("replyTo").get("deleted").asBoolean()).isTrue();

        JsonNode pins = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/pins", conversationId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pins).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pinned_messages WHERE message_id = ?", Integer.class, messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?", Integer.class, messageId)).isZero();

        UUID inboxMessageId = send(otherConversationId, alice, "latest preview");
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", otherConversationId, inboxMessageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        JsonNode inbox = objectMapper.readTree(mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode entry = findById(inbox, otherConversationId);
        assertThat(entry.get("lastMessage").has("contentSnippet")).isTrue();
        assertThat(entry.get("lastMessage").get("contentSnippet").isNull()).isTrue();
        assertThat(entry.get("lastMessage").get("deletedAt").isTextual()).isTrue();
    }

    @Test
    void searchUsesLiteralAccentInsensitiveSubstringAndStableCursorPages() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        send(conversationId, alice, "Cà phê hôm nay");
        send(conversationId, bob, "CA PHE after work");
        send(conversationId, alice, "literal %_ and quote's value");
        UUID deletedId = send(conversationId, alice, "deleted phrase stays hidden");
        send(conversationId, bob, "page match oldest");
        send(conversationId, alice, "page match middle");
        send(conversationId, bob, "page match newest");
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, deletedId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        JsonNode accentMatches = search(conversationId, alice, "ca phe", 20, null);
        assertThat(accentMatches.get("items").size()).isEqualTo(2);
        assertThat(accentMatches.get("items").get(0).get("content").asText()).contains("CA PHE");
        assertThat(search(conversationId, alice, "ca", 20, null).get("items")).hasSize(2);

        String maxQuery = "edge" + "x".repeat(96);
        send(conversationId, alice, maxQuery);
        assertThat(search(conversationId, alice, maxQuery, 20, null).get("items")).hasSize(1);

        JsonNode literal = search(conversationId, alice, "%_", 20, null);
        assertThat(literal.get("items").size()).isEqualTo(1);
        assertThat(literal.get("items").get(0).get("content").asText()).contains("%_");
        assertThat(search(conversationId, alice, "quote's", 20, null).get("items").size()).isEqualTo(1);
        assertThat(search(conversationId, alice, "deleted", 20, null).get("items")).isEmpty();

        JsonNode firstPage = search(conversationId, alice, "page", 2, null);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
        long cursor = firstPage.get("nextBeforeSeq").asLong();
        JsonNode secondPage = search(conversationId, alice, "page", 2, cursor);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
        assertThat(secondPage.get("items").get(0).get("seq").asLong()).isLessThan(cursor);

        assertError("VALIDATION_FAILED", 400,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(alice)).param("q", "x")));
        assertError("VALIDATION_FAILED", 400,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(alice)).param("q", "x".repeat(101))));
        assertError("NOT_MEMBER", 403,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(carol)).param("q", "page")));
    }

    @Test
    void editHonorsUnicodeAndLengthBoundariesRejectsNulAndKeepsEditedAtMonotonic() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID messageId = send(conversationId, alice, "seed");

        for (String content : List.of(
                "x",
                "a".repeat(2_000),
                "b".repeat(2_001),
                "😀",
                "👨‍👩‍👧‍👦",
                "مرحبا بالعالم",
                "e\u0301",
                "x".repeat(10_000))) {
            JsonNode updated = objectMapper.readTree(patchMessage(conversationId, messageId, alice, content)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(updated.get("content").asText()).isEqualTo(content);
            assertThat(updated.get("editedAt").isTextual()).isTrue();
        }

        Instant futurePriorEdit = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("UPDATE messages SET edited_at = ? WHERE id = ?",
                Timestamp.from(futurePriorEdit), messageId);
        JsonNode monotonic = objectMapper.readTree(patchMessage(conversationId, messageId, alice, "after future")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(Instant.parse(monotonic.get("editedAt").asText())).isAfter(futurePriorEdit);

        assertError("VALIDATION_FAILED", 400,
                patchMessage(conversationId, messageId, alice, "   "));
        assertError("VALIDATION_FAILED", 400,
                patchMessage(conversationId, messageId, alice, "x".repeat(10_001)));
        assertError("VALIDATION_FAILED", 400,
                patchMessage(conversationId, messageId, alice, "contains\0nul"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM messages WHERE id = ?", String.class, messageId))
                .isEqualTo("after future");
    }

    @Test
    void searchHandlesNfcNfdCaseUnicodeSpacesAndLikeMetacharacters() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        String nfdCafe = Normalizer.normalize("café", Normalizer.Form.NFD);
        send(conversationId, alice, "Đường ưỡn " + nfdCafe + " A_B 100% x\\y");

        assertThat(search(conversationId, alice, "đư", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "ĐƯ", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "café", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, Normalizer.normalize("café", Normalizer.Form.NFD),
                20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "a_b", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "100%", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "x\\y", 20, null).get("items")).hasSize(1);
        assertThat(search(conversationId, alice, "  100%  ", 20, null).get("items")).hasSize(1);
        assertError("VALIDATION_FAILED", 400,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(alice)).param("q", "   ")));
    }

    @Test
    void deletedMessageRejectsNewReactionsAndPinsButCanStillBeRepliedTo() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID messageId = send(conversationId, alice, "deleted source");
        mockMvc.perform(post("/api/v1/messages/{id}/reactions", messageId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"👍\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/conversations/{id}/pins", conversationId)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("messageId", messageId.toString()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        assertError("MESSAGE_DELETED", 409,
                mockMvc.perform(post("/api/v1/messages/{id}/reactions", messageId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"❤️\"}")));
        assertError("MESSAGE_DELETED", 409,
                mockMvc.perform(post("/api/v1/conversations/{id}/pins", conversationId)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("messageId", messageId.toString())))));

        UUID replyId = send(conversationId, bob, "reply after deletion", messageId);
        JsonNode history = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode reply = findById(history.get("items"), replyId);
        assertThat(reply.get("replyTo").get("deleted").asBoolean()).isTrue();
        assertThat(reply.get("replyTo").has("content")).isTrue();
        assertThat(reply.get("replyTo").get("content").isNull()).isTrue();
    }

    @Test
    void groupAdminCannotDeleteAnotherAuthorsMessageAndRemovedMemberLosesMessageAccess() throws Exception {
        JsonNode group = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "QA permissions",
                                "memberIds", List.of(bob.id().toString(), carol.id().toString())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID groupId = UUID.fromString(group.get("id").asText());
        UUID aliceMessage = send(groupId, alice, "admin-authored message");
        UUID bobMessage = send(groupId, bob, "member-authored message");

        assertError("NOT_MESSAGE_AUTHOR", 403,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", groupId, bobMessage)
                        .header("Authorization", bearer(alice))));
        assertError("NOT_MESSAGE_AUTHOR", 403,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", groupId, aliceMessage)
                        .header("Authorization", bearer(bob))));

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/members/{userId}", groupId, bob.id())
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        assertError("NOT_MEMBER", 403,
                patchMessage(groupId, aliceMessage, bob, "removed edit"));
        assertError("NOT_MEMBER", 403,
                mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", groupId, bobMessage)
                        .header("Authorization", bearer(bob))));
        assertError("NOT_MEMBER", 403,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", groupId)
                        .header("Authorization", bearer(bob)).param("q", "message")));
    }

    @Test
    void historyAndSearchCursorsStayExclusiveAcrossSoftDeleteAndConcurrentInsert() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID oldest = send(conversationId, alice, "cursor match oldest");
        UUID second = send(conversationId, bob, "cursor match second");
        UUID third = send(conversationId, alice, "cursor match third");
        UUID newest = send(conversationId, bob, "cursor match newest");

        JsonNode historyFirst = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)).param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historyFirst.get("items")).hasSize(2);
        assertThat(historyFirst.get("hasMore").asBoolean()).isTrue();
        long historyCursor = historyFirst.get("nextBeforeSeq").asLong();
        assertThat(historyFirst.get("items").get(0).get("id").asText()).isEqualTo(newest.toString());
        assertThat(historyFirst.get("items").get(1).get("id").asText()).isEqualTo(third.toString());

        JsonNode searchFirst = search(conversationId, alice, "cursor", 2, null);
        assertThat(searchFirst.get("items")).hasSize(2);
        long searchCursor = searchFirst.get("nextBeforeSeq").asLong();

        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, newest)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, newest)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());
        UUID inserted = send(conversationId, alice, "cursor match inserted after first page");

        JsonNode historyAgain = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)).param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode deletedFromHistory = findById(historyAgain.get("items"), newest);
        assertThat(deletedFromHistory.get("deletedAt").isTextual()).isTrue();

        JsonNode historyOlder = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice))
                        .param("limit", "2").param("beforeSeq", Long.toString(historyCursor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historyOlder.get("items")).hasSize(2);
        assertThat(historyOlder.get("hasMore").asBoolean()).isFalse();
        assertThat(historyOlder.get("items").get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(historyOlder.get("items").get(1).get("id").asText()).isEqualTo(oldest.toString());
        for (JsonNode item : historyOlder.get("items")) {
            assertThat(item.get("id").asText()).isNotEqualTo(inserted.toString());
        }

        JsonNode searchOlder = search(conversationId, alice, "cursor", 2, searchCursor);
        assertThat(searchOlder.get("items")).hasSize(2);
        assertThat(searchOlder.get("hasMore").asBoolean()).isFalse();
        assertThat(searchOlder.get("items").get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(searchOlder.get("items").get(1).get("id").asText()).isEqualTo(oldest.toString());
        for (JsonNode item : searchOlder.get("items")) {
            assertThat(item.get("id").asText()).isNotEqualTo(inserted.toString());
        }
    }

    @Test
    void historyAndSearchPageLimitsHaveExactHasMoreAtTheirBoundaries() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        for (int i = 0; i < 101; i++) {
            seedMessage(conversationId, alice, "bulk-match-" + i);
        }

        JsonNode historyZero = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)).param("limit", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historyZero.get("items")).hasSize(1);
        assertThat(historyZero.get("hasMore").asBoolean()).isTrue();

        JsonNode historyOne = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)).param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historyOne.get("items")).hasSize(1);
        assertThat(historyOne.get("hasMore").asBoolean()).isTrue();

        long historyCursor = -1;
        for (int limit : List.of(100, 101)) {
            JsonNode historyPage = objectMapper.readTree(mockMvc.perform(get(
                                    "/api/v1/conversations/{id}/messages", conversationId)
                            .header("Authorization", bearer(alice)).param("limit", Integer.toString(limit)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(historyPage.get("items")).hasSize(100);
            assertThat(historyPage.get("hasMore").asBoolean()).isTrue();
            historyCursor = historyPage.get("nextBeforeSeq").asLong();
        }
        JsonNode historyLastPage = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)).param("limit", "100")
                        .param("beforeSeq", Long.toString(historyCursor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historyLastPage.get("items")).hasSize(1);
        assertThat(historyLastPage.get("hasMore").asBoolean()).isFalse();

        assertThat(search(conversationId, alice, "bulk-match", 1, null).get("items")).hasSize(1);
        JsonNode searchPage = search(conversationId, alice, "bulk-match", 50, null);
        assertThat(searchPage.get("items")).hasSize(50);
        assertThat(searchPage.get("hasMore").asBoolean()).isTrue();
        JsonNode searchLastPage = search(conversationId, alice, "bulk-match", 50,
                searchPage.get("nextBeforeSeq").asLong());
        assertThat(searchLastPage.get("items")).hasSize(50);
        assertThat(searchLastPage.get("hasMore").asBoolean()).isTrue();
        JsonNode searchFinalPage = search(conversationId, alice, "bulk-match", 50,
                searchLastPage.get("nextBeforeSeq").asLong());
        assertThat(searchFinalPage.get("items")).hasSize(1);
        assertThat(searchFinalPage.get("hasMore").asBoolean()).isFalse();

        assertError("VALIDATION_FAILED", 400,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(alice)).param("q", "bulk-match").param("limit", "0")));
        assertError("VALIDATION_FAILED", 400,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages/search", conversationId)
                        .header("Authorization", bearer(alice)).param("q", "bulk-match").param("limit", "51")));
    }

    @Test
    void concurrentEditAndDeleteHasNoServerErrorAndDeletionWins() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID messageId = send(conversationId, alice, "concurrent original");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> edit = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent edit gate timed out");
                }
                return patchMessage(conversationId, messageId, alice, "racing edit").andReturn();
            });
            Future<MvcResult> delete = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent delete gate timed out");
                }
                return mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}",
                                conversationId, messageId)
                        .header("Authorization", bearer(alice))).andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int editStatus = edit.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int deleteStatus = delete.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(editStatus).isIn(200, 409);
            assertThat(deleteStatus).isEqualTo(204);

            JsonNode history = objectMapper.readTree(mockMvc.perform(get(
                                    "/api/v1/conversations/{id}/messages", conversationId)
                            .header("Authorization", bearer(alice)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            JsonNode row = findById(history.get("items"), messageId);
            assertThat(row.get("deletedAt").isTextual()).isTrue();
            assertThat(row.has("content")).isTrue();
            assertThat(row.get("content").isNull()).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8490" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID openDirect(TestUser first, TestUser second) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", second.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private UUID send(UUID conversationId, TestUser sender, String content) throws Exception {
        return send(conversationId, sender, content, null);
    }

    private UUID send(UUID conversationId, TestUser sender, String content, UUID replyToId) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("clientMessageId", UUID.randomUUID().toString());
        request.put("type", "TEXT");
        request.put("content", content);
        request.put("replyToId", replyToId);
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private UUID seedMessage(UUID conversationId, TestUser sender, String content) {
        return messageService.send(sender.id(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", content, null, List.of()))
                .message().id();
    }

    private org.springframework.test.web.servlet.ResultActions patchMessage(
            UUID conversationId, UUID messageId, TestUser user, String content) throws Exception {
        return mockMvc.perform(patch("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))));
    }

    private JsonNode search(UUID conversationId, TestUser user, String query, int limit, Long beforeSeq)
            throws Exception {
        var request = get("/api/v1/conversations/{id}/messages/search", conversationId)
                .header("Authorization", bearer(user)).param("q", query).param("limit", Integer.toString(limit));
        if (beforeSeq != null) {
            request.param("beforeSeq", Long.toString(beforeSeq));
        }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    private static JsonNode findById(JsonNode array, UUID id) {
        for (JsonNode item : array) {
            if (id.toString().equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Expected id not present in response");
    }

    private void assertError(String code, int status, org.springframework.test.web.servlet.ResultActions response)
            throws Exception {
        MvcResult result = response.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .is(status)).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("error").get("code").asText()).isEqualTo(code);
    }
}
