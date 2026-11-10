package com.nexachat.retention;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.GroupService;
import com.nexachat.conversation.dto.PatchGroupRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Populated-PostgreSQL coverage for the opt-in retention jobs.
 *
 * <p>Every test owns synthetic users and conversations in the private IT
 * database selected by {@code IT_DB_URL}; it never uses the application DB.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@TestPropertySource(properties = "nexachat.storage.root=${java.io.tmpdir}/nexachat-retention-it")
class RetentionJobsIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private RetentionJobRunner retentionJobRunner;
    @Autowired
    private RetentionProperties retentionProperties;
    @Autowired
    private GroupService groupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID conversationId;
    private final List<UUID> conversationIds = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();

    @BeforeEach
    void createBaseRows() {
        firstUserId = insertUser("first");
        secondUserId = insertUser("second");
        conversationId = insertConversation(firstUserId);
    }

    @AfterEach
    void removeSyntheticRowsAndFiles() throws Exception {
        for (String key : storageKeys) {
            Files.deleteIfExists(storagePath(key));
        }
        if (firstUserId != null && secondUserId != null) {
            jdbcTemplate.update("DELETE FROM message_attachments WHERE owner_id IN (?, ?)",
                    firstUserId, secondUserId);
        }
        for (UUID id : conversationIds) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id);
        }
        if (firstUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", firstUserId);
        }
        if (secondUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", secondUserId);
        }
        conversationIds.clear();
        storageKeys.clear();
    }

    @Test
    void registersRetentionJobRunner() {
        assertThat(applicationContext.containsBean("retentionJobRunner"))
                .as("the bounded retention coordinator should be registered")
                .isTrue();
    }

    @Test
    void defaultSwitchesKeepEveryJobDisabled() {
        retentionProperties.setEnabled(false);

        assertThat(retentionJobRunner.runEnabledJobs()).isEmpty();
    }

    @Test
    void purgesExpiredAndRevokedRefreshTokensAndStaleDeviceTokensAtAgeBoundaries() {
        UUID expired = insertRefreshToken("now() - interval '31 days'", "NULL");
        UUID revoked = insertRefreshToken("now() + interval '1 day'", "now() - interval '31 days'");
        UUID recent = insertRefreshToken("now() - interval '29 days'", "NULL");
        UUID active = insertRefreshToken("now() + interval '1 day'", "NULL");

        UUID staleDevice = insertDeviceToken("old-device", "now() - interval '181 days'");
        UUID recentDevice = insertDeviceToken("recent-device", "now() - interval '179 days'");
        UUID activeDevice = insertDeviceToken("active-device", "now()");

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.REFRESH_TOKENS, false)
                .rowsAffected()).isEqualTo(2);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.DEVICE_TOKENS, false)
                .rowsAffected()).isEqualTo(1);

        assertThat(rowExists("refresh_tokens", "id", expired)).isFalse();
        assertThat(rowExists("refresh_tokens", "id", revoked)).isFalse();
        assertThat(rowExists("refresh_tokens", "id", recent)).isTrue();
        assertThat(rowExists("refresh_tokens", "id", active)).isTrue();
        assertThat(rowExists("user_device_tokens", "id", staleDevice)).isFalse();
        assertThat(rowExists("user_device_tokens", "id", recentDevice)).isTrue();
        assertThat(rowExists("user_device_tokens", "id", activeDevice)).isTrue();
    }

    @Test
    void rerunningTheSameRetentionJobIsIdempotent() {
        UUID expired = insertRefreshToken("now() - interval '31 days'", "NULL");
        UUID revoked = insertRefreshToken("now() + interval '1 day'", "now() - interval '31 days'");

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.REFRESH_TOKENS, false)
                .rowsAffected()).isEqualTo(2);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.REFRESH_TOKENS, false)
                .rowsAffected()).isZero();

        assertThat(rowExists("refresh_tokens", "id", expired)).isFalse();
        assertThat(rowExists("refresh_tokens", "id", revoked)).isFalse();
    }

    @Test
    void removesFinishedJoinAndInviteHistoryButKeepsPendingAndCurrentRows() {
        UUID revokedConversation = insertConversation(firstUserId);
        UUID expiredConversation = insertConversation(firstUserId);
        UUID usedUpConversation = insertConversation(firstUserId);
        UUID activeConversation = insertConversation(firstUserId);
        UUID revoked = insertInviteLink(revokedConversation, null, null, 0,
                "now() - interval '31 days'", firstUserId);
        UUID expired = insertInviteLink(expiredConversation,
                timeAgo(Duration.ofDays(31)), null, 0, "now() - interval '40 days'", null);
        UUID usedUp = insertInviteLink(usedUpConversation, null, 1, 1,
                "now() - interval '31 days'", null);
        UUID active = insertInviteLink(activeConversation,
                timeAhead(Duration.ofDays(10)), 2, 0, "now()", null);

        UUID oldReviewed = insertJoinRequest(revokedConversation, revoked, "APPROVED",
                timeAgo(Duration.ofDays(31)));
        UUID recentReviewed = insertJoinRequest(conversationId, null, "REJECTED",
                timeAgo(Duration.ofDays(29)));
        UUID pending = insertJoinRequest(activeConversation, active, "PENDING", null);
        UUID oldLinkBan = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO group_invite_bans (id, conversation_id, user_id, invite_link_id, created_at)
                VALUES (?, ?, ?, ?, now() - interval '31 days')
                """, oldLinkBan, revokedConversation, secondUserId, revoked);
        UUID durableBan = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO group_invite_bans (id, conversation_id, user_id, invite_link_id, created_at)
                VALUES (?, ?, ?, NULL, now() - interval '500 days')
                """, durableBan, activeConversation, secondUserId);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.JOIN_REQUESTS, false)
                .rowsAffected()).isEqualTo(1);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.INVITE_BANS, false)
                .rowsAffected()).isEqualTo(1);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.INVITE_LINKS, false)
                .rowsAffected()).isEqualTo(3);

        assertThat(rowExists("group_join_requests", "id", oldReviewed)).isFalse();
        assertThat(rowExists("group_join_requests", "id", recentReviewed)).isTrue();
        assertThat(rowExists("group_join_requests", "id", pending)).isTrue();
        assertThat(rowExists("group_invite_bans", "id", oldLinkBan)).isFalse();
        assertThat(rowExists("group_invite_bans", "id", durableBan)).isTrue();
        assertThat(rowExists("group_invite_links", "id", revoked)).isFalse();
        assertThat(rowExists("group_invite_links", "id", expired)).isFalse();
        assertThat(rowExists("group_invite_links", "id", usedUp)).isFalse();
        assertThat(rowExists("group_invite_links", "id", active)).isTrue();
    }

    @Test
    void purgesExpiredIdempotencyRowsButKeepsRowsInsideTtl() {
        String oldKey = insertIdempotency("old-idem", "now() - interval '26 hours'");
        String recentKey = insertIdempotency("recent-idem", "now() - interval '22 hours'");

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.IDEMPOTENCY, false)
                .rowsAffected()).isEqualTo(1);

        assertThat(idempotencyExists(oldKey)).isFalse();
        assertThat(idempotencyExists(recentKey)).isTrue();
    }

    @Test
    void purgesFinishedRemindersWithoutTouchingRepeatingOrRecentReminders() {
        UUID oldFired = insertReminder("NONE", "now() - interval '31 days'");
        UUID recentFired = insertReminder("NONE", "now() - interval '29 days'");
        UUID repeating = insertReminder("WEEKLY", "NULL");

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.REMINDERS, false)
                .rowsAffected()).isEqualTo(1);

        assertThat(rowExists("conversation_reminders", "id", oldFired)).isFalse();
        assertThat(rowExists("conversation_reminders", "id", recentFired)).isTrue();
        assertThat(rowExists("conversation_reminders", "id", repeating)).isTrue();
    }

    @Test
    void expiresSummaryRagAndReactionCachesAtTheirStoredBoundaries() {
        UUID messageId = insertMessage("event source", null);
        long oldReaction = insertReactionEvent(messageId, "now() - interval '31 days'");
        long recentReaction = insertReactionEvent(messageId, "now() - interval '29 days'");
        UUID expiredSummary = insertSummary("now() - interval '1 second'");
        UUID liveSummary = insertSummary("now() + interval '1 day'");
        UUID expiredRag = insertRagCache("now() - interval '1 second'");
        UUID liveRag = insertRagCache("now() + interval '1 day'");
        OffsetDateTime oldMinute = timeAgo(Duration.ofDays(2));
        OffsetDateTime freshMinute = timeAgo(Duration.ofHours(6));
        OffsetDateTime oldDay = timeAgo(Duration.ofDays(3));
        OffsetDateTime freshDay = timeAgo(Duration.ofDays(1));
        insertRateWindow("summary_request_limits", "MINUTE", oldMinute);
        insertRateWindow("summary_request_limits", "MINUTE", freshMinute);
        insertRateWindow("summary_request_limits", "DAY", oldDay);
        insertRateWindow("summary_request_limits", "DAY", freshDay);
        insertRateWindow("rag_ask_request_limits", "MINUTE", oldMinute);
        insertRateWindow("rag_ask_request_limits", "MINUTE", freshMinute);
        insertRateWindow("rag_ask_request_limits", "DAY", oldDay);
        insertRateWindow("rag_ask_request_limits", "DAY", freshDay);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.REACTION_EVENTS, false)
                .rowsAffected()).isEqualTo(1);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.SUMMARIES, false)
                .rowsAffected()).isEqualTo(1);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.SUMMARY_LIMITS, false)
                .rowsAffected()).isEqualTo(2);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.RAG_CACHE, false)
                .rowsAffected()).isEqualTo(1);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.RAG_LIMITS, false)
                .rowsAffected()).isEqualTo(2);

        assertThat(rowExists("reaction_events", "id", oldReaction)).isFalse();
        assertThat(rowExists("reaction_events", "id", recentReaction)).isTrue();
        assertThat(rowExists("message_summaries", "id", expiredSummary)).isFalse();
        assertThat(rowExists("message_summaries", "id", liveSummary)).isTrue();
        assertThat(rowExists("rag_ask_cache", "id", expiredRag)).isFalse();
        assertThat(rowExists("rag_ask_cache", "id", liveRag)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM summary_request_limits WHERE user_id = ?
                  AND window_start IN (?, ?)
                """, Integer.class, firstUserId, freshMinute, freshDay)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rag_ask_request_limits WHERE user_id = ?
                  AND window_start IN (?, ?)
                """, Integer.class, firstUserId, freshMinute, freshDay)).isEqualTo(2);
    }

    @Test
    void agesClosedPollsIntoVersionedTombstonesAndKeepsActivePolls() {
        UUID oldClosed = insertPoll("old closed", timeAgo(Duration.ofDays(366)), null);
        UUID recentClosed = insertPoll("recent closed", timeAgo(Duration.ofDays(364)), null);
        UUID active = insertPoll("active", null, timeAhead(Duration.ofDays(2)));
        UUID oldDeleted = insertPoll("old deleted", null, null);
        jdbcTemplate.update("UPDATE polls SET deleted_at = now() - interval '366 days' WHERE id = ?",
                oldDeleted);
        long oldClosedVersion = pollVersion(oldClosed);
        long oldDeletedVersion = pollVersion(oldDeleted);
        UUID oldClosedOption = insertPollOption(oldClosed, "old option");
        UUID oldDeletedOption = insertPollOption(oldDeleted, "deleted option");
        insertPollVote(oldClosed, oldClosedOption);
        insertPollVote(oldDeleted, oldDeletedOption);
        UUID activeOption = insertPollOption(active, "active option");
        insertPollVote(active, activeOption);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.POLLS, false)
                .rowsAffected()).isGreaterThanOrEqualTo(4);

        assertThat(pollDeletedAt(oldClosed)).isNotNull();
        assertThat(pollVersion(oldClosed)).isGreaterThan(oldClosedVersion);
        assertThat(pollQuestion(oldClosed)).isEqualTo("[retained]");
        assertThat(pollVersion(oldDeleted)).isEqualTo(oldDeletedVersion);
        assertThat(pollQuestion(oldDeleted)).isEqualTo("[retained]");
        assertThat(rowExists("poll_options", "id", oldClosedOption)).isFalse();
        assertThat(rowExists("poll_options", "id", oldDeletedOption)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM poll_votes WHERE poll_id IN (?, ?)",
                Integer.class, oldClosed, oldDeleted)).isZero();
        assertThat(pollDeletedAt(recentClosed)).isNull();
        assertThat(pollQuestion(recentClosed)).isEqualTo("recent closed");
        assertThat(pollDeletedAt(active)).isNull();
        assertThat(pollQuestion(active)).isEqualTo("active");
        assertThat(rowExists("poll_options", "id", activeOption)).isTrue();
    }

    @Test
    void purgesOnlyEndedCallRecords() {
        UUID oldEnded = insertCallRecord("ENDED", timeAgo(Duration.ofDays(366)));
        UUID recentEnded = insertCallRecord("ENDED", timeAgo(Duration.ofDays(364)));
        UUID active = insertCallRecord("ACTIVE", null);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.CALL_RECORDS, false)
                .rowsAffected()).isEqualTo(1);

        assertThat(rowExists("call_records", "call_id", oldEnded)).isFalse();
        assertThat(rowExists("call_records", "call_id", recentEnded)).isTrue();
        assertThat(rowExists("call_records", "call_id", active)).isTrue();
    }

    @Test
    void cleansOrphanedFilesWithoutRemovingLiveOrReferencedFiles() throws Exception {
        UUID deletedMessage = insertMessage("deleted payload", timeAgo(Duration.ofDays(31)));
        UUID liveMessage = insertMessage("live payload", null);
        String oldOrphanKey = insertAttachment(null, "now() - interval '25 hours'");
        String recentOrphanKey = insertAttachment(null, "now() - interval '23 hours'");
        String tombstoneKey = insertAttachment(deletedMessage, "now() - interval '40 days'");
        String liveKey = insertAttachment(liveMessage, "now() - interval '40 days'");
        String avatarReferencedKey = insertAttachment(null, "now() - interval '40 days'");
        String groupAvatarReferencedKey = insertAttachment(null, "now() - interval '40 days'");
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?",
                "/api/v1/files/" + avatarReferencedKey, firstUserId);
        jdbcTemplate.update("UPDATE conversations SET avatar_url = ? WHERE id = ?",
                "https://nexachat.example/api/v1/files/" + groupAvatarReferencedKey + "?v=1",
                conversationId);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.ATTACHMENTS, false)
                .rowsAffected()).isEqualTo(2);

        assertThat(Files.exists(storagePath(oldOrphanKey))).isFalse();
        assertThat(Files.exists(storagePath(tombstoneKey))).isFalse();
        assertThat(Files.exists(storagePath(recentOrphanKey))).isTrue();
        assertThat(Files.exists(storagePath(liveKey))).isTrue();
        assertThat(Files.exists(storagePath(avatarReferencedKey))).isTrue();
        assertThat(Files.exists(storagePath(groupAvatarReferencedKey))).isTrue();
        assertThat(attachmentExists(oldOrphanKey)).isFalse();
        assertThat(attachmentExists(tombstoneKey)).isFalse();
        assertThat(attachmentExists(recentOrphanKey)).isTrue();
        assertThat(attachmentExists(liveKey)).isTrue();
        assertThat(attachmentExists(avatarReferencedKey)).isTrue();
        assertThat(attachmentExists(groupAvatarReferencedKey)).isTrue();
    }

    @Test
    void canonicalizesEncodedGroupAvatarPathBeforeAttachmentCleanup() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO conversation_members (conversation_id, user_id, role)
                VALUES (?, ?, 'ADMIN')
                """, conversationId, firstUserId);
        String storageKey = "attachments/00000000-0000-0000-0000-00000000000a.txt";
        insertAttachment(storageKey, null, "now() - interval '40 days'");
        String encodedUrl = "/api/v1/files/attachments/00000000-0000-0000-0000-00000000000%61.txt?v=1";

        var update = groupService.updateGroup(firstUserId, conversationId,
                new PatchGroupRequest(null, encodedUrl));

        assertThat(update.avatarUrl()).isEqualTo("/api/v1/files/" + storageKey + "?v=1");
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.ATTACHMENTS, false)
                .rowsAffected()).isZero();
        assertThat(attachmentExists(storageKey)).isTrue();
        assertThat(Files.exists(storagePath(storageKey))).isTrue();
    }

    @Test
    void cleanupRetainsLegacyEncodedConversationAvatarReferences() throws Exception {
        String storageKey = "attachments/00000000-0000-0000-0000-00000000000a.txt";
        insertAttachment(storageKey, null, "now() - interval '40 days'");
        String legacyAvatarUrl = "/api/v1/files/attachments/00000000-0000-0000-0000-00000000000%61.txt?v=legacy";
        jdbcTemplate.update("UPDATE conversations SET avatar_url = ? WHERE id = ?",
                legacyAvatarUrl, conversationId);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.ATTACHMENTS, false)
                .rowsAffected()).isZero();
        assertThat(attachmentExists(storageKey)).isTrue();
        assertThat(Files.exists(storagePath(storageKey))).isTrue();
    }

    @Test
    void groupUpdateRejectsFileAfterAttachmentDeleteRollback() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO conversation_members (conversation_id, user_id, role)
                VALUES (?, ?, 'ADMIN')
                """, conversationId, firstUserId);
        String storageKey = insertAttachment(null, "now() - interval '40 days'");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_retention_attachment_delete()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced retention rollback';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_retention_attachment_delete
                BEFORE DELETE ON message_attachments
                FOR EACH ROW EXECUTE FUNCTION fail_retention_attachment_delete()
                """);

        try {
            RetentionJobRunner.JobRunResult cleanupResult = retentionJobRunner.runNow(
                    RetentionJobRunner.Job.ATTACHMENTS, false);

            assertThat(cleanupResult.rowsAffected()).isZero();
            assertThat(cleanupResult.errors()).isEqualTo(1);
            assertThat(attachmentExists(storageKey)).isTrue();
            assertThat(Files.exists(storagePath(storageKey))).isFalse();
            ApiException updateFailure = catchThrowableOfType(ApiException.class,
                    () -> groupService.updateGroup(firstUserId, conversationId,
                            new PatchGroupRequest(null, "/api/v1/files/" + storageKey)));
            assertThat(updateFailure.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_retention_attachment_delete ON message_attachments");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_retention_attachment_delete()");
        }
    }

    @Test
    void groupAvatarReferenceLockPreventsConcurrentAttachmentDeletion() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO conversation_members (conversation_id, user_id, role)
                VALUES (?, ?, 'ADMIN')
                """, conversationId, firstUserId);
        String storageKey = insertAttachment(null, "now() - interval '40 days'");
        String avatarUrl = "/api/v1/files/" + storageKey;
        RetentionJobRunner.JobRunResult[] cleanupResult = new RetentionJobRunner.JobRunResult[1];

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).execute(status -> {
                groupService.updateGroup(firstUserId, conversationId,
                        new PatchGroupRequest(null, avatarUrl));
                Future<RetentionJobRunner.JobRunResult> cleanup = executor.submit(
                        () -> retentionJobRunner.runNow(RetentionJobRunner.Job.ATTACHMENTS, false));
                try {
                    cleanupResult[0] = cleanup.get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("Concurrent attachment cleanup did not finish", failure);
                }
                assertThat(cleanupResult[0].rowsAffected()).isZero();
                assertThat(Files.exists(storagePath(storageKey))).isTrue();
                return null;
            });
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM conversations WHERE id = ?", String.class, conversationId))
                .isEqualTo(avatarUrl);
        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.ATTACHMENTS, false)
                .rowsAffected()).isZero();
        assertThat(attachmentExists(storageKey)).isTrue();
        assertThat(Files.exists(storagePath(storageKey))).isTrue();
    }

    @Test
    void purgesOldTombstonePayloadAndEmbeddingsButKeepsMessagesAndRecentContent() {
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)",
                conversationId, firstUserId);
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)",
                conversationId, secondUserId);
        UUID oldTombstone = insertMessage("old private text", timeAgo(Duration.ofDays(31)));
        UUID recentTombstone = insertMessage("recent private text", timeAgo(Duration.ofDays(29)));
        UUID liveMessage = insertMessage("live content", null);
        addEmbedding(oldTombstone);
        addMention(oldTombstone);

        assertThat(retentionJobRunner.runNow(RetentionJobRunner.Job.MESSAGE_TOMBSTONES, false)
                .rowsAffected()).isGreaterThanOrEqualTo(3);

        assertThat(messageExists(oldTombstone)).isTrue();
        assertThat(messageContent(oldTombstone)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message_embeddings WHERE message_id = ?",
                Integer.class, oldTombstone)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message_mentions WHERE message_id = ?",
                Integer.class, oldTombstone)).isZero();
        assertThat(messageContent(recentTombstone)).isEqualTo("recent private text");
        assertThat(messageContent(liveMessage)).isEqualTo("live content");
    }

    @Test
    void dryRunCountsWithoutChangingRowsAndRecordsMetrics() {
        String oldKey = insertIdempotency("dry-run", "now() - interval '26 hours'");
        io.micrometer.core.instrument.Counter dryRunRuns = meterRegistry.counter(
                "nexachat.retention.job.runs", "job", "idempotency", "outcome", "dry_run");
        double before = dryRunRuns.count();

        RetentionJobRunner.JobRunResult result = retentionJobRunner.runNow(
                RetentionJobRunner.Job.IDEMPOTENCY, true);

        assertThat(result.rowsAffected()).isEqualTo(1);
        assertThat(idempotencyExists(oldKey)).isTrue();
        assertThat(dryRunRuns.count()).isEqualTo(before + 1);
        assertThat(meterRegistry.get("nexachat.retention.rows.observed")
                .tag("job", "idempotency").counter().count()).isGreaterThan(0);
    }

    @Test
    void concurrentRunnersSkipLockedRowsAndDeleteDisjointBatches() throws Exception {
        for (int index = 0; index < 220; index++) {
            insertIdempotency("concurrent-" + index, "now() - interval '2 days'");
        }
        List<String> lockedKeys = new ArrayList<>();
        CountDownLatch rowsLocked = new CountDownLatch(1);
        CountDownLatch releaseLocks = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                lockedKeys.addAll(jdbcTemplate.query("""
                        SELECT key FROM request_idempotency
                        WHERE user_id = ? AND key LIKE 'concurrent-%'
                          AND created_at <= now() - interval '24 hours'
                        ORDER BY created_at, user_id, key
                        LIMIT 100
                        FOR UPDATE
                        """, (rs, rowNum) -> rs.getString(1), firstUserId));
                rowsLocked.countDown();
                try {
                    if (!releaseLocks.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release deterministic row locks");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding deterministic row locks", interrupted);
                }
                return null;
            }));

            try {
                assertThat(rowsLocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(lockedKeys).hasSize(100);
                Future<RetentionJobRunner.JobRunResult> first = executor.submit(
                        () -> retentionJobRunner.runNow(RetentionJobRunner.Job.IDEMPOTENCY, false));
                Future<RetentionJobRunner.JobRunResult> second = executor.submit(
                        () -> retentionJobRunner.runNow(RetentionJobRunner.Job.IDEMPOTENCY, false));

                int removed = first.get(10, TimeUnit.SECONDS).rowsAffected()
                        + second.get(10, TimeUnit.SECONDS).rowsAffected();
                assertThat(removed).isEqualTo(120);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM request_idempotency
                        WHERE user_id = ? AND key LIKE 'concurrent-%'
                        """, Integer.class, firstUserId)).isEqualTo(100);

                String placeholders = String.join(",", java.util.Collections.nCopies(lockedKeys.size(), "?"));
                Object[] parameters = new Object[lockedKeys.size() + 1];
                parameters[0] = firstUserId;
                for (int index = 0; index < lockedKeys.size(); index++) {
                    parameters[index + 1] = lockedKeys.get(index);
                }
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM request_idempotency WHERE user_id = ? AND key IN ("
                                + placeholders + ")",
                        Integer.class, parameters)).isEqualTo(100);
            } finally {
                releaseLocks.countDown();
            }
            lockHolder.get(5, TimeUnit.SECONDS);
        }
    }

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        String phoneTail = Long.toString(Math.floorMod(
                UUID.randomUUID().getMostSignificantBits(), 10_000_000_000L));
        phoneTail = "0".repeat(10 - phoneTail.length()) + phoneTail;
        jdbcTemplate.update("""
                INSERT INTO users (id, phone, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """, id, "+849" + phoneTail, "x".repeat(60), "retention-" + label);
        return id;
    }

    private UUID insertConversation(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO conversations (id, type, title, created_by)
                VALUES (?, 'GROUP', 'retention fixture', ?)
                """, id, ownerId);
        conversationIds.add(id);
        return id;
    }

    private UUID insertRefreshToken(String expiresAtSql, String revokedAtSql) {
        UUID id = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, created_at, expires_at, revoked_at)
                VALUES (?, ?, ?, now() - interval '40 days', %s, %s)
                """.formatted(expiresAtSql, revokedAtSql), id, firstUserId, hash);
        return id;
    }

    private UUID insertDeviceToken(String token, String lastUsedAtSql) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO user_device_tokens (id, user_id, token, created_at, updated_at, last_used_at)
                VALUES (?, ?, ?, now() - interval '200 days', now() - interval '200 days', %s)
                """.formatted(lastUsedAtSql), id, firstUserId, token);
        return id;
    }

    private UUID insertInviteLink(UUID convId, OffsetDateTime expiresAt, Integer maxUses,
                                  int useCount, String updatedAtSql, UUID revokedBy) {
        UUID id = UUID.randomUUID();
        String tokenHash = UUID.randomUUID().toString().replace("-", "");
        String revokedAtSql = revokedBy == null ? "NULL" : "now() - interval '31 days'";
        jdbcTemplate.update("""
                INSERT INTO group_invite_links
                    (id, conversation_id, created_by, token_hash, requires_approval, expires_at,
                     max_uses, use_count, revoked_at, revoked_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, false, ?, ?, ?, %s, ?, now() - interval '40 days', %s)
                """.formatted(revokedAtSql, updatedAtSql), id, convId, firstUserId,
                tokenHash, expiresAt, maxUses, useCount, revokedBy);
        return id;
    }

    private UUID insertJoinRequest(UUID convId, UUID linkId, String status, OffsetDateTime reviewedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO group_join_requests
                    (id, conversation_id, invite_link_id, user_id, status, created_at, reviewed_at, reviewed_by)
                VALUES (?, ?, ?, ?, ?, now() - interval '40 days', ?, ?)
                """, id, convId, linkId, secondUserId, status, reviewedAt,
                "PENDING".equals(status) ? null : firstUserId);
        return id;
    }

    private UUID insertReminder(String repeatRule, String firedAtSql) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO conversation_reminders
                    (id, conversation_id, created_by, title, remind_at, anchor_at, repeat_rule,
                     fired_at, last_fired_at, created_at, updated_at)
                VALUES (?, ?, ?, 'retention fixture', now() + interval '1 day', now() + interval '1 day',
                        ?, %s, %s, now() - interval '40 days', now() - interval '40 days')
                """.formatted(firedAtSql, firedAtSql), id, conversationId, firstUserId, repeatRule);
        return id;
    }

    private UUID insertPoll(String question, OffsetDateTime closedAt, OffsetDateTime closesAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO polls (id, conversation_id, created_by, question, multiple_choice, anonymous,
                                   closes_at, closed_at, closed_by, deleted_at, created_at)
                VALUES (?, ?, ?, ?, false, false, ?, ?, ?, NULL, now() - interval '400 days')
                """, id, conversationId, firstUserId, question, closesAt, closedAt,
                closedAt == null ? null : firstUserId);
        return id;
    }

    private UUID insertPollOption(UUID pollId, String label) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO poll_options (id, poll_id, position, label) VALUES (?, ?, 0, ?)
                """, id, pollId, label);
        return id;
    }

    private void insertPollVote(UUID pollId, UUID optionId) {
        jdbcTemplate.update("INSERT INTO poll_votes (poll_id, option_id, user_id) VALUES (?, ?, ?)",
                pollId, optionId, secondUserId);
    }

    private UUID insertCallRecord(String state, OffsetDateTime endedAt) {
        UUID id = UUID.randomUUID();
        if ("ENDED".equals(state)) {
            jdbcTemplate.update("""
                    INSERT INTO call_records
                        (call_id, conversation_id, caller_id, callee_id, state, end_reason,
                         started_at, answered_at, ended_at, duration_seconds)
                    VALUES (?, ?, ?, ?, 'ENDED', 'COMPLETED', ?, ?, ?, 8)
                    """, id, conversationId, firstUserId, secondUserId,
                    timeAgo(Duration.ofDays(370)), endedAt, endedAt);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO call_records
                        (call_id, conversation_id, caller_id, callee_id, state, end_reason,
                         started_at, answered_at, ended_at, duration_seconds)
                    VALUES (?, ?, ?, ?, 'ACTIVE', NULL, now() - interval '1 minute', now(), NULL, NULL)
                    """, id, conversationId, firstUserId, secondUserId);
        }
        return id;
    }

    private UUID insertMessage(String content, OffsetDateTime deletedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type,
                                      content, deleted_at, created_at)
                VALUES (?, ?, ?, ?, 'TEXT', ?, ?, now() - interval '400 days')
                """, id, conversationId, firstUserId, UUID.randomUUID(), content, deletedAt);
        return id;
    }

    private long insertReactionEvent(UUID messageId, String createdAtSql) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action, created_at)
                VALUES (?, ?, ?, '👍', 'REMOVE', %s) RETURNING id
                """.formatted(createdAtSql), Long.class, conversationId, messageId, secondUserId);
    }

    private UUID insertSummary(String expiresAtSql) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO message_summaries
                    (id, conversation_id, from_seq, to_seq, content_hash, provider, language,
                     payload_json, created_at, expires_at)
                VALUES (?, ?, 1, 1, ?, 'LOCAL', 'EN', '{\"summary\":\"fixture\"}'::jsonb,
                        now() - interval '2 days', %s)
                """.formatted(expiresAtSql), id, conversationId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        return id;
    }

    private UUID insertRagCache(String expiresAtSql) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_ask_cache
                    (id, conversation_id, model, question_hash, question_vector, language,
                     payload_json, cited_message_ids, cited_content_hash, created_at, expires_at)
                VALUES (?, ?, 'fixture', ?, decode('00000000', 'hex'), 'EN', '{\"answer\":\"fixture\"}'::jsonb,
                        ARRAY[]::uuid[], ?, now() - interval '2 days', %s)
                """.formatted(expiresAtSql), id, conversationId,
                UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        return id;
    }

    private void insertRateWindow(String table, String type, OffsetDateTime windowStart) {
        if (!"summary_request_limits".equals(table) && !"rag_ask_request_limits".equals(table)) {
            throw new IllegalArgumentException("Unsupported test table");
        }
        jdbcTemplate.update("INSERT INTO " + table + " (user_id, window_type, window_start, request_count) "
                        + "VALUES (?, ?, ?, 1)", firstUserId, type, windowStart);
    }

    private String insertIdempotency(String key, String createdAtSql) {
        jdbcTemplate.update("""
                INSERT INTO request_idempotency (user_id, key, endpoint, payload_hash, response_hash, created_at)
                VALUES (?, ?, '/fixture', 'payload', 'response', %s)
                """.formatted(createdAtSql), firstUserId, key);
        return key;
    }

    private String insertAttachment(UUID messageId, String createdAtSql) throws Exception {
        String key = "attachments/" + UUID.randomUUID() + ".txt";
        return insertAttachment(key, messageId, createdAtSql);
    }

    private String insertAttachment(String key, UUID messageId, String createdAtSql) throws Exception {
        Path file = storagePath(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "synthetic attachment");
        storageKeys.add(key);
        jdbcTemplate.update("""
                INSERT INTO message_attachments
                    (id, message_id, owner_id, storage_key, original_name, mime_type, size_bytes, created_at)
                VALUES (?, ?, ?, ?, 'fixture.txt', 'text/plain', 20, %s)
                """.formatted(createdAtSql), UUID.randomUUID(), messageId, firstUserId, key);
        return key;
    }

    private void addEmbedding(UUID messageId) {
        jdbcTemplate.update("""
                INSERT INTO message_embeddings
                    (message_id, conversation_id, model, dim, vector, content_hash)
                VALUES (?, ?, 'fixture', 1, decode('00000000', 'hex'), ?)
                """, messageId, conversationId, UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private void addMention(UUID messageId) {
        jdbcTemplate.update("""
                INSERT INTO message_mentions
                    (message_id, conversation_id, user_id, mention_offset, mention_length)
                VALUES (?, ?, ?, 0, 4)
                """, messageId, conversationId, secondUserId);
    }

    private boolean rowExists(String table, String column, Object id) {
        if (!List.of("refresh_tokens", "user_device_tokens", "group_join_requests",
                "group_invite_bans", "group_invite_links", "conversation_reminders", "poll_options",
                "call_records", "message_summaries", "rag_ask_cache", "reaction_events").contains(table)) {
            throw new IllegalArgumentException("Unsupported test table");
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE " + column + " = ?)",
                Boolean.class, id));
    }

    private boolean idempotencyExists(String key) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM request_idempotency WHERE user_id = ? AND key = ?)
                """, Boolean.class, firstUserId, key));
    }

    private boolean attachmentExists(String key) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM message_attachments WHERE storage_key = ?)
                """, Boolean.class, key));
    }

    private boolean messageExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM messages WHERE id = ?)", Boolean.class, id));
    }

    private String messageContent(UUID id) {
        return jdbcTemplate.queryForObject("SELECT content FROM messages WHERE id = ?", String.class, id);
    }

    private String pollQuestion(UUID id) {
        return jdbcTemplate.queryForObject("SELECT question FROM polls WHERE id = ?", String.class, id);
    }

    private long pollVersion(UUID id) {
        return jdbcTemplate.queryForObject("SELECT version FROM polls WHERE id = ?", Long.class, id);
    }

    private OffsetDateTime pollDeletedAt(UUID id) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM polls WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class), id);
    }

    private Path storagePath(String key) {
        return Path.of(System.getProperty("java.io.tmpdir"), "nexachat-retention-it", key);
    }

    private OffsetDateTime timeAgo(Duration duration) {
        return OffsetDateTime.ofInstant(Instant.now().minus(duration), ZoneOffset.UTC);
    }

    private OffsetDateTime timeAhead(Duration duration) {
        return OffsetDateTime.ofInstant(Instant.now().plus(duration), ZoneOffset.UTC);
    }
}
