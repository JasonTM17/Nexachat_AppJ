package com.nexachat.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.*;
import com.nexachat.message.dto.*;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
class LegacySyncOverflowIT {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Instant SINCE = NOW.minusSeconds(1000);
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired MessageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMemberRepository members;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;

    @Test
    void walkingFiftyOneEditsAndRecallsLosesNothing() {
        walk(false);
    }

    @Test
    void boundaryTiesAreReturnedTogetherAcrossConversations() {
        walk(true);
    }

    private void walk(boolean ties) {
        Fixture a = fixture(51, 51, ties, 0);
        Fixture b = fixtureFor(a.user(), 51, 51, ties, ties ? 0 : 300);
        Map<UUID, Set<UUID>> edits = new HashMap<>();
        Map<UUID, Set<UUID>> recalls = new HashMap<>();
        Instant since = SINCE;
        boolean more = true;
        for (int page = 0; page < 10 && more; page++) {
            SyncResponse response = sync(a.user(), List.of(a.watermark(), b.watermark()), since);
            assertThat(response.serverTime()).isAfter(since).isBeforeOrEqualTo(NOW);
            if (!ties && page == 0) {
                assertThat(response.serverTime()).isEqualTo(SINCE.plusSeconds(50));
                assertThat(response.deltas()).allSatisfy(delta -> {
                    assertThat(delta.editedMessages()).hasSize(50);
                    assertThat(delta.deletedMessageIds()).hasSize(50);
                    assertThat(delta.hasMore()).isTrue();
                });
            }
            for (var delta : response.deltas()) {
                edits.computeIfAbsent(delta.conversationId(), key -> new HashSet<>())
                        .addAll(delta.editedMessages().stream().map(MessageResponse::id).toList());
                recalls.computeIfAbsent(delta.conversationId(), key -> new HashSet<>()).addAll(delta.deletedMessageIds());
                if (ties && page == 0) {
                    assertThat(delta.editedMessages()).hasSize(51);
                    assertThat(delta.deletedMessageIds()).hasSize(51);
                    assertThat(delta.hasMore()).isFalse();
                }
            }
            more = response.deltas().stream().anyMatch(SyncResponse.ConversationDelta::hasMore);
            since = response.serverTime();
        }
        assertThat(more).isFalse();
        for (Fixture fixture : List.of(a, b)) {
            assertThat(edits.get(fixture.conversation())).containsExactlyInAnyOrderElementsOf(fixture.edits());
            assertThat(recalls.get(fixture.conversation())).containsExactlyInAnyOrderElementsOf(fixture.recalls());
        }
    }

    @Test
    void normalSmallSyncRetainsExactLegacyJsonBytes() throws Exception {
        Fixture fixture = fixture(1, 1, false, 0);
        SyncResponse actual = sync(fixture.user(), List.of(fixture.watermark()), SINCE);
        var row = jdbc.queryForMap("SELECT * FROM messages WHERE id=?", fixture.edits().get(0));
        MessageResponse edited = new MessageResponse(fixture.edits().get(0), fixture.conversation(),
                ((Number) row.get("seq")).longValue(), fixture.user(), "Sync user", "TEXT", "edited",
                (UUID) row.get("client_message_id"), null, null, List.of(), List.of(), SINCE.minusSeconds(10),
                SINCE.plusSeconds(1), null, List.of(), false, 0L, 1L);
        SyncResponse expected = new SyncResponse(List.of(new SyncResponse.ConversationDelta(
                fixture.conversation(), List.of(), List.of(edited), fixture.recalls(), List.of(), fixture.maxSeq(),
                false, null, false, false, null, false, false, null)), NOW);
        assertThat(mapper.writeValueAsBytes(actual)).isEqualTo(mapper.writeValueAsBytes(expected));
    }

    private SyncResponse sync(UUID user, List<SyncRequest.ConversationWatermark> watermarks, Instant since) {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return service.syncDelta(user, new SyncRequest(watermarks, 50, since));
    }

    private Fixture fixture(int edited, int recalled, boolean ties, int offset) {
        User user = users.saveAndFlush(User.createGoogleOnly("Sync user", UUID.randomUUID() + "@example.test", NOW));
        return fixtureFor(user.getId(), edited, recalled, ties, offset);
    }

    private Fixture fixtureFor(UUID user, int edited, int recalled, boolean ties, int offset) {
        UUID conversation = new TransactionTemplate(transactions).execute(status -> {
            User managed = users.getReferenceById(user);
            Conversation group = conversations.saveAndFlush(Conversation.group(managed, "Sync group"));
            members.saveAndFlush(ConversationMember.join(group, managed, ConversationMember.Role.ADMIN));
            return group.getId();
        });
        List<UUID> editIds = new ArrayList<>();
        List<UUID> recallIds = new ArrayList<>();
        long maxSeq = 0;
        for (int index = 0; index < edited + recalled; index++) {
            boolean edit = index < edited;
            int ordinal = edit ? index : index - edited;
            Instant changed = SINCE.plusSeconds(offset + (ties ? 1 : ordinal + 1));
            UUID id = UUID.randomUUID();
            Long seq = jdbc.queryForObject("""
                    INSERT INTO messages (id,conversation_id,sender_id,client_message_id,type,content,created_at,edited_at,deleted_at)
                    VALUES (?,?,?,?,'TEXT',?,?,?,?) RETURNING seq
                    """, Long.class,
                    id, conversation, user, UUID.randomUUID(), edit ? "edited" : null,
                    java.sql.Timestamp.from(SINCE.minusSeconds(10)), edit ? java.sql.Timestamp.from(changed) : null,
                    edit ? null : java.sql.Timestamp.from(changed));
            maxSeq = Math.max(maxSeq, seq);
            (edit ? editIds : recallIds).add(id);
        }
        return new Fixture(user, conversation, editIds, recallIds, maxSeq);
    }

    private record Fixture(UUID user, UUID conversation, List<UUID> edits, List<UUID> recalls, long maxSeq) {
        SyncRequest.ConversationWatermark watermark() {
            return new SyncRequest.ConversationWatermark(conversation, maxSeq);
        }
    }
}
