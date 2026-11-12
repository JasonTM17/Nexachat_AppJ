package com.nexachat.message;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.GroupService;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import org.awaitility.Awaitility;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
 * {@code clientMessageId}, and no sequence draw on a committed idempotent
 * replay. A concurrent insert conflict may still consume a global sequence.
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
    private GroupService groupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private TestUser dave;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "SEQ" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
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
    void sendEditRecallAllocateDistinctTransactionalChangeVersions() throws Exception {
        UUID conversationId = createGroup(alice, "Change-version-" + System.nanoTime(), bob);
        groupService.addMembers(alice.id(), conversationId,
                new AddMemberRequest(List.of(carol.id(), dave.id())));
        List<Long> systemVersions = jdbcTemplate.queryForList("""
                SELECT change_version FROM messages
                WHERE conversation_id = ? AND type = 'SYSTEM'
                ORDER BY change_version
                """, Long.class, conversationId);
        assertThat(systemVersions).containsExactly(1L, 2L, 3L);
        long startingVersion = jdbcTemplate.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?", Long.class, conversationId);
        assertThat(startingVersion).isEqualTo(3L);
        UUID clientMessageId = UUID.randomUUID();

        MessageResponse created = messageService.send(alice.id(), conversationId,
                new SendMessageRequest(clientMessageId, "TEXT", "original", null, List.of())).message();
        assertThat(created.changeVersion()).isEqualTo(startingVersion + 1);
        assertThat(created.generation()).isEqualTo(1L);

        MessageResponse replay = messageService.send(alice.id(), conversationId,
                new SendMessageRequest(clientMessageId, "TEXT", "original", null, List.of())).message();
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.changeVersion()).isEqualTo(created.changeVersion());

        MessageResponse edited = messageService.edit(alice.id(), conversationId, created.id(), "updated");
        assertThat(edited.changeVersion()).isEqualTo(startingVersion + 2);
        MessageResponse noOpEdit = messageService.edit(alice.id(), conversationId, created.id(), " updated ");
        assertThat(noOpEdit.changeVersion()).isEqualTo(edited.changeVersion());

        messageService.delete(alice.id(), conversationId, created.id());
        Long persistedMessageVersion = jdbcTemplate.queryForObject(
                "SELECT change_version FROM messages WHERE id = ?", Long.class, created.id());
        Long persistedConversationVersion = jdbcTemplate.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?", Long.class, conversationId);
        assertThat(persistedMessageVersion).isEqualTo(startingVersion + 3);
        assertThat(persistedConversationVersion).isEqualTo(startingVersion + 3);

        messageService.delete(alice.id(), conversationId, created.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?", Long.class, conversationId))
                .isEqualTo(startingVersion + 3);
    }

    @Test
    void concurrentDuplicateClientMessageIdReturnsOneStoredMessage() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-race-" + System.nanoTime(), bob);
        UUID clientMessageId = UUID.randomUUID();
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
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSendsCannotClaimOneAttachmentOrInsertBeforeClaim() throws Exception {
        UUID conversationId = createGroup(alice, "Seq-attachment-race-" + System.nanoTime(), bob);
        UUID attachmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO message_attachments
                    (id, owner_id, storage_key, original_name, mime_type, size_bytes)
                VALUES (?, ?, ?, 'shared.png', 'image/png', 8)
                """, attachmentId, alice.id(), "seq-race/" + attachmentId);
        long seqBefore = currentSequenceValue();
        UUID firstClientMessageId = UUID.randomUUID();
        UUID secondClientMessageId = UUID.randomUUID();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("""
                    SELECT id FROM message_attachments WHERE id = ? FOR UPDATE
                    """, UUID.class, attachmentId);
            rowLocked.countDown();
            try {
                if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release attachment row lock");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding attachment row lock", interrupted);
            }
            return null;
        }));

        try {
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<MessageResponse>> sends = List.of(
                    executor.submit(() -> sendAttachment(conversationId, firstClientMessageId, attachmentId)),
                    executor.submit(() -> sendAttachment(conversationId, secondClientMessageId, attachmentId)));
            awaitAttachmentClaimWaiters();

            // Both send transactions must still be claiming the attachment;
            // neither may have allocated a message sequence yet.
            assertThat(currentSequenceValue()).isEqualTo(seqBefore);

            releaseLock.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);

            List<MessageResponse> successful = new ArrayList<>();
            int rejected = 0;
            for (Future<MessageResponse> send : sends) {
                try {
                    successful.add(send.get(10, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(ApiException.class);
                    rejected++;
                }
            }

            assertThat(successful).hasSize(1);
            assertThat(rejected).isEqualTo(1);
            MessageResponse winner = successful.get(0);
            List<UUID> storedClientMessageIds = jdbcTemplate.queryForList("""
                    SELECT client_message_id FROM messages
                    WHERE conversation_id = ? AND client_message_id IN (?, ?)
                    """, UUID.class, conversationId, firstClientMessageId, secondClientMessageId);
            assertThat(storedClientMessageIds).containsExactly(winner.clientMessageId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT message_id FROM message_attachments WHERE id = ?", UUID.class, attachmentId))
                    .isEqualTo(winner.id());
            assertThat(currentSequenceValue()).isEqualTo(seqBefore + 1);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private MessageResponse sendAttachment(UUID conversationId, UUID clientMessageId, UUID attachmentId) {
        return messageService.send(alice.id(), conversationId,
                new SendMessageRequest(clientMessageId, "IMAGE", null, null, List.of(attachmentId)))
                .message();
    }

    private void awaitAttachmentClaimWaiters() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity activity
                    WHERE activity.pid <> pg_backend_pid()
                      AND activity.datname = current_database()
                      AND activity.wait_event_type = 'Lock'
                    """, Integer.class);
            assertThat(waiting).isEqualTo(2);
        });
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

    private int countMessages(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(MessageSequenceGuaranteeIT.class);
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
