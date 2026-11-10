package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the documented message-sequence contract: global {@code BIGSERIAL},
 * monotonic per conversation, gaps allowed, no duplicate rows for one
 * {@code clientMessageId}, and no sequence draw on idempotent replay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageSequenceGuaranteeIT {

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
    private TestUser carol;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "SEQ" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    @Test
    void globalBigserialInterleavesSequencesAcrossConversations() throws Exception {
        UUID roomA = createGroup(alice, "Seq-gap-A-" + System.nanoTime(), bob);
        UUID roomB = createGroup(alice, "Seq-gap-B-" + System.nanoTime(), bob, carol);

        long seqBefore = currentSequenceValue();
        MessageResponse firstA = sendMessage(roomA, "a1");
        MessageResponse crossB = sendMessage(roomB, "b1");
        MessageResponse secondA = sendMessage(roomA, "a2");

        assertThat(firstA.seq()).isLessThan(crossB.seq());
        assertThat(crossB.seq()).isLessThan(secondA.seq());
        assertThat(secondA.seq() - firstA.seq()).isGreaterThan(1);

        List<Long> roomASeqs = jdbcTemplate.queryForList(
                "SELECT seq FROM messages WHERE conversation_id = ? ORDER BY seq",
                Long.class, roomA);
        assertThat(roomASeqs).contains(firstA.seq(), secondA.seq());
        assertThat(roomASeqs).doesNotContain(crossB.seq());

        long seqAfter = currentSequenceValue();
        assertThat(seqAfter).isGreaterThanOrEqualTo(secondA.seq());
        assertThat(seqAfter).isGreaterThan(seqBefore);
    }

    @Test
    void idempotentReplayDoesNotAdvanceSequenceOrCreateExtraRow() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-idem-" + System.nanoTime(), bob);
        UUID clientMessageId = UUID.randomUUID();
        long seqBefore = currentSequenceValue();

        MessageResponse created = sendMessage(conversationId, clientMessageId, "once");
        long seqAfterFirst = currentSequenceValue();
        assertThat(seqAfterFirst).isGreaterThanOrEqualTo(created.seq());

        MessageResponse replay = sendMessage(conversationId, clientMessageId, "once");
        long seqAfterReplay = currentSequenceValue();

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.seq()).isEqualTo(created.seq());
        assertThat(seqAfterReplay).isEqualTo(seqAfterFirst);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND client_message_id = ?",
                Integer.class, conversationId, clientMessageId);
        assertThat(rowCount).isEqualTo(1);
        assertThat(seqBefore).isLessThan(created.seq());
    }

    @Test
    void concurrentDuplicateClientMessageIdDrawsOneSequenceOnly() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-race-" + System.nanoTime(), bob);
        UUID clientMessageId = UUID.randomUUID();
        long maxSeqBefore = maxSeqInConversation(conversationId);
        int rowsBefore = countMessages(conversationId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MessageResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return sendMessage(conversationId, clientMessageId, "race");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MessageResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
            MessageResponse second = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(first.seq()).isEqualTo(second.seq());
            assertThat(first.id()).isEqualTo(second.id());

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND client_message_id = ?",
                    Integer.class, conversationId, clientMessageId);
            assertThat(rows).isEqualTo(1);

            assertThat(countMessages(conversationId)).isEqualTo(rowsBefore + 1);
            assertThat(maxSeqInConversation(conversationId) - maxSeqBefore).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void missingAttachmentRejectedBeforeInsertDoesNotConsumeSequence() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-att-" + System.nanoTime(), bob);
        long seqBefore = currentSequenceValue();
        int rowsBefore = countMessages(conversationId);

        UUID missingAttachment = UUID.randomUUID();
        assertThatThrownBy(() -> messageService.send(alice.id(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "IMAGE", null, null, List.of(missingAttachment))))
                .isInstanceOf(ApiException.class);

        assertThat(countMessages(conversationId)).isEqualTo(rowsBefore);
        assertThat(currentSequenceValue()).isEqualTo(seqBefore);
    }

    @Test
    void perConversationOrderRemainsStrictlyIncreasingDespiteGaps() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-mono-" + System.nanoTime(), bob);
        UUID other = createGroup(alice, "Seq-x-" + System.nanoTime(), bob);
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i == 2) {
                sendMessage(other, "interleave");
            }
            MessageResponse row = sendMessage(conversationId, "m" + i);
            seqs.add(row.seq());
        }
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
        }
        assertThat(seqs).hasSize(5).doesNotHaveDuplicates();
    }

    private MessageResponse sendMessage(UUID conversationId, String content) {
        return sendMessage(conversationId, UUID.randomUUID(), content);
    }

    private MessageResponse sendMessage(UUID conversationId, UUID clientMessageId, String content) {
        return messageService.send(alice.id(), conversationId,
                new SendMessageRequest(clientMessageId, "TEXT", content, null, List.of()))
                .message();
    }

    private long currentSequenceValue() {
        return jdbcTemplate.queryForObject("SELECT last_value FROM messages_seq_seq", Long.class);
    }

    private long maxSeqInConversation(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
    }

    private int countMessages(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8492" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
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

    private UUID createGroup(TestUser admin, String title, TestUser... members) throws Exception {
        List<String> memberIds = new ArrayList<>();
        for (TestUser member : members) {
            memberIds.add(member.id().toString());
        }
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title, "memberIds", memberIds))))
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
