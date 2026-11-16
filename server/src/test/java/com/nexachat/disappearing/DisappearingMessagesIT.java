package com.nexachat.disappearing;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.disappearing.dto.DisappearingRequest;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Populated-PostgreSQL coverage for the disappearing-messages feature: the
 * set-timer API's authz and allow-list, insert-time {@code expires_at}
 * stamping, non-retroactive history, and the purge path that expires messages
 * through the shared soft-delete cascade (reactions, pins, tombstone, delta
 * sync).
 *
 * <p>Every test owns synthetic users and conversations in the private IT
 * container; it never touches the application DB.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@TestPropertySource(properties = "nexachat.storage.root=${java.io.tmpdir}/nexachat-disappearing-it")
@Testcontainers
class DisappearingMessagesIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nexachat_disappearing_it")
            .withUsername("nexachat")
            .withPassword("nexachat");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DisappearingMessagesService disappearingService;
    @Autowired
    private DisappearingMessagePurger purger;
    @Autowired
    private MessageService messageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;
    private UUID bobId;
    private UUID carolId;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> conversationIds = new ArrayList<>();

    @BeforeEach
    void createUsers() {
        aliceId = insertUser("alice");
        bobId = insertUser("bob");
        carolId = insertUser("carol");
    }

    @AfterEach
    void removeSyntheticRows() {
        for (UUID id : conversationIds) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        conversationIds.clear();
        userIds.clear();
    }

    @Test
    void directMemberSetsTimerAndNewMessagesStampExpiry() {
        UUID conversationId = insertConversation("DIRECT", aliceId,
                member(aliceId, "MEMBER"), member(bobId, "MEMBER"));

        ConversationUpdatePayload payload = disappearingService.setTimer(
                aliceId, conversationId, new DisappearingRequest(3600));

        assertThat(payload.disappearingSeconds()).isEqualTo(3600);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT disappearing_seconds FROM conversations WHERE id = ?",
                Integer.class, conversationId)).isEqualTo(3600);
        assertThat(countWhere(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM'"
                        + " AND content LIKE '%disappear after 1 hour%'",
                conversationId)).isEqualTo(1);

        MessageService.SendResult sent = messageService.send(aliceId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "gone in an hour",
                        null, List.of()));

        assertThat(sent.message().expiresAt()).isNotNull();
        assertThat(sent.message().expiresAt())
                .isEqualTo(sent.message().createdAt().plusSeconds(3600));
    }

    @Test
    void timerChangeDoesNotRetroactivelyStampExistingHistory() {
        UUID conversationId = insertConversation("DIRECT", aliceId,
                member(aliceId, "MEMBER"), member(bobId, "MEMBER"));
        UUID beforeId = messageService.send(aliceId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "sent before the timer",
                        null, List.of())).message().id();

        disappearingService.setTimer(aliceId, conversationId, new DisappearingRequest(86400));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM messages WHERE id = ?", String.class, beforeId))
                .isNull();
        UUID afterId = messageService.send(bobId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "sent after",
                        null, List.of())).message().id();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM messages WHERE id = ?", String.class, afterId))
                .isNotNull();
    }

    @Test
    void groupTimerIsAdminOnlyAndAllowListed() {
        UUID conversationId = insertConversation("GROUP", aliceId,
                member(aliceId, "ADMIN"), member(bobId, "MEMBER"));

        ApiException memberFailure = catchThrowableOfType(ApiException.class,
                () -> disappearingService.setTimer(
                        bobId, conversationId, new DisappearingRequest(3600)));
        assertThat(memberFailure.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);

        ApiException outsiderFailure = catchThrowableOfType(ApiException.class,
                () -> disappearingService.setTimer(
                        carolId, conversationId, new DisappearingRequest(3600)));
        assertThat(outsiderFailure.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);

        ApiException validationFailure = catchThrowableOfType(ApiException.class,
                () -> disappearingService.setTimer(
                        aliceId, conversationId, new DisappearingRequest(42)));
        assertThat(validationFailure.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);

        ApiException missingConversation = catchThrowableOfType(ApiException.class,
                () -> disappearingService.setTimer(
                        aliceId, UUID.randomUUID(), new DisappearingRequest(3600)));
        assertThat(missingConversation.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);

        assertThat(disappearingService.setTimer(
                aliceId, conversationId, new DisappearingRequest(604800))
                .disappearingSeconds()).isEqualTo(604800);
    }

    @Test
    void idempotentSetEmitsOneSystemLineAndClearingRestoresUnstampedSends() {
        UUID conversationId = insertConversation("DIRECT", aliceId,
                member(aliceId, "MEMBER"), member(bobId, "MEMBER"));

        disappearingService.setTimer(aliceId, conversationId, new DisappearingRequest(3600));
        disappearingService.setTimer(aliceId, conversationId, new DisappearingRequest(3600));
        assertThat(countWhere(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM'",
                conversationId)).isEqualTo(1);

        ConversationUpdatePayload cleared = disappearingService.setTimer(
                aliceId, conversationId, new DisappearingRequest(0));
        assertThat(cleared.disappearingSeconds()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT disappearing_seconds FROM conversations WHERE id = ?",
                String.class, conversationId)).isNull();
        assertThat(countWhere(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM'"
                        + " AND content LIKE '%turned off disappearing messages%'",
                conversationId)).isEqualTo(1);

        UUID unstamped = messageService.send(bobId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "stays",
                        null, List.of())).message().id();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM messages WHERE id = ?", String.class, unstamped))
                .isNull();
    }

    @Test
    void purgeExpiresThroughSharedDeleteCascadeAndIsIdempotent() {
        UUID conversationId = insertConversation("DIRECT", aliceId,
                member(aliceId, "MEMBER"), member(bobId, "MEMBER"));
        disappearingService.setTimer(aliceId, conversationId, new DisappearingRequest(3600));
        UUID expiring = messageService.send(aliceId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "expire me",
                        null, List.of())).message().id();
        UUID live = messageService.send(bobId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "still alive",
                        null, List.of())).message().id();
        jdbcTemplate.update(
                "INSERT INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?)",
                expiring, bobId, "👍");
        jdbcTemplate.update(
                "INSERT INTO pinned_messages (conversation_id, message_id, pinned_by) VALUES (?, ?, ?)",
                conversationId, expiring, aliceId);
        jdbcTemplate.update(
                "UPDATE messages SET expires_at = now() - interval '1 minute' WHERE id = ?",
                expiring);

        int purged = purger.purgeBatch();

        assertThat(purged).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM messages WHERE id = ?", String.class, expiring))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM messages WHERE id = ?", String.class, live))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, expiring)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pinned_messages WHERE message_id = ?",
                Integer.class, expiring)).isZero();
        assertThat(purger.purgeBatch()).isZero();
    }

    @Test
    void deltaSyncCarriesTimerAndExpiry() {
        UUID conversationId = insertConversation("DIRECT", aliceId,
                member(aliceId, "MEMBER"), member(bobId, "MEMBER"));
        disappearingService.setTimer(aliceId, conversationId, new DisappearingRequest(3600));
        messageService.send(aliceId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "sync me",
                        null, List.of()));

        SyncResponse response = messageService.syncDelta(bobId, new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 0L)),
                null, null));

        assertThat(response.deltas()).hasSize(1);
        SyncResponse.ConversationDelta delta = response.deltas().get(0);
        assertThat(delta.disappearingSeconds()).isEqualTo(3600);
        assertThat(delta.newMessages())
                .anySatisfy(message -> assertThat(message.expiresAt()).isNotNull());
    }

    private int countWhere(String sql, UUID conversationId) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private record MemberSpec(UUID userId, String role) {
    }

    private MemberSpec member(UUID userId, String role) {
        return new MemberSpec(userId, role);
    }

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, phone, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """, id, ItPhoneRanges.nextPhone(DisappearingMessagesIT.class),
                "x".repeat(60), "disappear-" + label);
        userIds.add(id);
        return id;
    }

    private UUID insertConversation(String type, UUID creator, MemberSpec... members) {
        UUID id = UUID.randomUUID();
        String directKey = "DIRECT".equals(type) ? "dk-" + id : null;
        jdbcTemplate.update("""
                INSERT INTO conversations (id, type, title, direct_key, created_by)
                VALUES (?, ?, 'disappearing fixture', ?, ?)
                """, id, type, directKey, creator);
        for (MemberSpec member : members) {
            jdbcTemplate.update("""
                    INSERT INTO conversation_members (conversation_id, user_id, role)
                    VALUES (?, ?, ?)
                    """, id, member.userId(), member.role());
        }
        conversationIds.add(id);
        return id;
    }
}
