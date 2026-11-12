package com.nexachat.message;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delta sync when more new messages exist than one page can return ({@code hasMore})
 * and when the client resumes from the delivered max seq without gaps or duplicates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncDeltaMessageGapIT {

    private static final int PAGE_LIMIT = 50;
    private static final int MESSAGE_COUNT = PAGE_LIMIT + 5;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
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
    private MessageService messageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "ITG" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
    }

    @Test
    void gapLargerThanPageSizeResumesWithoutSkippingOrDuplicating() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            messageService.send(alice.id(), conversationId,
                    new SendMessageRequest(UUID.randomUUID(), "TEXT", "gap-" + i, null, List.of()));
        }
        long maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
        Instant since = Instant.now().minusSeconds(60);

        JsonNode first = syncDelta(bob, conversationId, 0L, since, PAGE_LIMIT);
        JsonNode delta = first.get("deltas").get(0);
        assertThat(delta.get("hasMore").asBoolean()).isTrue();
        List<Long> firstSeqs = seqs(delta);
        assertThat(firstSeqs).hasSize(PAGE_LIMIT);
        assertThat(firstSeqs.getFirst()).isLessThan(firstSeqs.getLast());

        JsonNode second = syncDelta(bob, conversationId, firstSeqs.getLast(), since, PAGE_LIMIT);
        JsonNode pageTwo = second.get("deltas").get(0);
        assertThat(pageTwo.get("hasMore").asBoolean()).isFalse();
        List<Long> secondSeqs = seqs(pageTwo);
        assertThat(secondSeqs).hasSize(MESSAGE_COUNT - PAGE_LIMIT);
        assertThat(secondSeqs.getFirst()).isGreaterThan(firstSeqs.getLast());
        assertThat(pageTwo.get("maxSeq").asLong()).isEqualTo(maxSeq);

        List<Long> merged = new ArrayList<>(firstSeqs);
        merged.addAll(secondSeqs);
        assertThat(merged).doesNotHaveDuplicates();
        assertThat(merged.getLast()).isEqualTo(maxSeq);
    }

    private List<Long> seqs(JsonNode delta) {
        List<Long> seqs = new ArrayList<>();
        delta.get("newMessages").forEach(item -> seqs.add(item.get("seq").asLong()));
        return seqs;
    }

    private JsonNode syncDelta(TestUser user, UUID conversationId, long lastSeq, Instant since, int limit)
            throws Exception {
        Map<String, Object> body = Map.of(
                "watermarks", List.of(Map.of(
                        "conversationId", conversationId.toString(),
                        "lastSeq", lastSeq)),
                "since", since.toString(),
                "limitPerConv", limit);
        MvcResult result = mockMvc.perform(post("/api/v1/sync")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(SyncDeltaMessageGapIT.class);
        var result = mockMvc.perform(post("/api/v1/auth/register")
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

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
