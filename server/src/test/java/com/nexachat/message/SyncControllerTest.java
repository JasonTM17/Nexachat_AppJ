package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.dto.MemberFlagsRow;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.ReactionEventId;
import com.nexachat.message.dto.ReactionRow;
import com.nexachat.message.dto.ReactionUpdatedPayload;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import com.nexachat.message.dto.SyncFence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncControllerTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageReactionRepository reactionRepository;
    @Mock
    private ReactionEventRepository reactionEventRepository;

    private MessageService messageService;
    private SyncController syncController;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final JwtPrincipal principal = new JwtPrincipal(userId);

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                reactionRepository,
                reactionEventRepository,
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                mock(ApplicationEventPublisher.class),
                new SyncProperties()
        );
        syncController = new SyncController(messageService);
    }

    @Test
    void syncDeltaReturnsDeltasAcrossWatermarks() {
        stubMembership();

        MessageRow row1 = new MessageRow(
                UUID.randomUUID(), conversationId, 101L, userId, "Test User",
                Message.Type.TEXT, "Hello delta", UUID.randomUUID(), null,
                Instant.now(), null, null);

        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of(row1));
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(105L);

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)),
                50,
                null
        );

        ResponseEntity<SyncResponse> response = syncController.syncDelta(principal, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deltas()).hasSize(1);
        assertThat(response.getBody().syncVersion()).isNull();

        SyncResponse.ConversationDelta delta = response.getBody().deltas().get(0);
        assertThat(delta.conversationId()).isEqualTo(conversationId);
        assertThat(delta.newMessages()).hasSize(1);
        assertThat(delta.newMessages().get(0).seq()).isEqualTo(101L);
        assertThat(delta.maxSeq()).isEqualTo(105L);
        assertThat(delta.hasMore()).isFalse();
        assertThat(delta.changes()).isNull();
        assertThat(delta.changeCursor()).isNull();
        assertThat(delta.changeUpperBound()).isNull();
        assertThat(delta.reset()).isNull();
    }

    @Test
    void v2MessagePageUsesLimitPlusOneAndReturnsRevisionContinuation() {
        stubMembership();
        when(messageRepository.findSyncFence(userId, conversationId))
                .thenReturn(Optional.of(new SyncFence(12L, 4L)));

        List<MessageRow> rows = List.of(
                v2Row(UUID.randomUUID(), 1L, 4L),
                v2Row(UUID.randomUUID(), 2L, 4L),
                v2Row(UUID.randomUUID(), 3L, 4L));
        when(messageRepository.findChangesBetween(eq(conversationId), eq(0L), eq(12L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    assertThat(pageable.getPageSize()).isEqualTo(3);
                    return rows;
                });

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 0L, null, 0L, null)),
                2, null, 2);
        SyncResponse response = syncController.syncDelta(principal, request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.syncVersion()).isEqualTo(2);
        SyncResponse.ConversationDelta delta = response.deltas().get(0);
        assertThat(delta.changes()).hasSize(2);
        assertThat(delta.changes()).extracting(MessageResponse::changeVersion).containsExactly(1L, 2L);
        assertThat(delta.changes()).allSatisfy(message -> assertThat(message.generation()).isEqualTo(4L));
        assertThat(delta.changeCursor()).isEqualTo(2L);
        assertThat(delta.changeUpperBound()).isEqualTo(12L);
        assertThat(delta.changeHasMore()).isTrue();
        assertThat(delta.reset()).isFalse();
        assertThat(delta.hasMore()).isTrue();
        assertThat(delta.newMessages()).isEmpty();
        assertThat(delta.editedMessages()).isEmpty();
        assertThat(delta.deletedMessageIds()).isEmpty();
    }

    @Test
    void v2AheadOrMismatchedGenerationReturnsResetOnly() {
        stubMembership();
        when(messageRepository.findSyncFence(userId, conversationId))
                .thenReturn(Optional.of(new SyncFence(10L, 3L)));

        SyncResponse ahead = syncController.syncDelta(principal, v2Request(11L, 3L)).getBody();
        SyncResponse mismatched = syncController.syncDelta(principal, v2Request(7L, 2L)).getBody();

        assertResetOnly(ahead.deltas().get(0), 10L, 3L);
        assertResetOnly(mismatched.deltas().get(0), 10L, 3L);
        verify(messageRepository, never()).findChangesBetween(eq(conversationId), anyLong(),
                anyLong(), any(Pageable.class));
        verify(reactionEventRepository, never()).lockConversationEvents(any());
    }

    @Test
    void disabledV2SwitchFallsBackToLegacyWireFields() throws Exception {
        stubMembership();
        SyncProperties disabled = new SyncProperties();
        disabled.setChangeCursorsEnabled(false);
        MessageService disabledService = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                reactionRepository,
                reactionEventRepository,
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                mock(ApplicationEventPublisher.class),
                disabled);
        when(messageRepository.findRowsAfter(eq(conversationId), eq(0L), any(Pageable.class)))
                .thenReturn(List.of());
        SyncRequest request = v2Request(0L, null);

        SyncResponse response = disabledService.syncDelta(userId, request);
        JsonNode json = mapper().valueToTree(response);

        assertThat(response.syncVersion()).isNull();
        assertThat(json.has("syncVersion")).isFalse();
        JsonNode delta = json.get("deltas").get(0);
        assertThat(delta.has("changes")).isFalse();
        assertThat(delta.has("changeCursor")).isFalse();
        assertThat(delta.has("changeHasMore")).isFalse();
        assertThat(delta.has("changeUpperBound")).isFalse();
        assertThat(delta.has("reset")).isFalse();
        assertThat(delta.has("generation")).isFalse();
        verify(messageRepository, never()).findSyncFence(userId, conversationId);
    }

    @Test
    void syncDeltaIncludesEditedDeletedAndReactionsWhenSinceIsGiven() {
        Instant since = Instant.now().minusSeconds(3600);
        stubMembership();

        MessageRow editedRow = new MessageRow(
                UUID.randomUUID(), conversationId, 95L, userId, "Test User",
                Message.Type.TEXT, "Edited text", UUID.randomUUID(), null,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(1800), null);

        UUID deletedId = UUID.randomUUID();
        UUID reactionMsgId = UUID.randomUUID();

        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.findEditedRowsSince(conversationId, 100L, since))
                .thenReturn(List.of(editedRow));
        when(messageRepository.findDeletedMessageIdsSince(conversationId, 100L, since))
                .thenReturn(List.of(deletedId));
        when(reactionEventRepository.findEventIdsSince(eq(conversationId), eq(since), any(), anyInt()))
                .thenReturn(List.of(new ReactionEventIdRow(1L, reactionMsgId)));
        when(reactionRepository.findRowsByMessageIdIn(any()))
                .thenReturn(List.of(new ReactionRow(reactionMsgId, userId, "❤️")));
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)),
                50,
                since
        );

        ResponseEntity<SyncResponse> response = syncController.syncDelta(principal, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SyncResponse.ConversationDelta delta = response.getBody().deltas().get(0);
        assertThat(delta.editedMessages()).hasSize(1);
        assertThat(delta.editedMessages().get(0).content()).isEqualTo("Edited text");
        assertThat(delta.deletedMessageIds()).containsExactly(deletedId);
        assertThat(delta.reactionUpdates()).hasSize(1);
        assertThat(delta.reactionUpdates().get(0).messageId()).isEqualTo(reactionMsgId);
        assertThat(delta.reactionUpdates().get(0).reactions()).hasSize(1);
        assertThat(delta.reactionUpdates().get(0).reactions().get(0).emoji()).isEqualTo("❤️");
    }

    @Test
    void syncDeltaRejectsEmptyWatermarks() {
        SyncRequest emptyRequest = new SyncRequest(List.of(), 50, null);
        assertThatThrownBy(() -> syncController.syncDelta(principal, emptyRequest))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void syncDeltaRejectsUnauthenticated() {
        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)), 50, null);
        assertThatThrownBy(() -> syncController.syncDelta(null, request))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.UNAUTHORIZED);
    }

    @Test
    void syncDeltaServerTimeIsCapturedBeforeTheQueriesRun() {
        Instant start = Instant.parse("2026-11-08T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        MessageService clockedService = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                reactionRepository,
                reactionEventRepository,
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                mock(ApplicationEventPublisher.class),
                new SyncProperties(),
                clock
        );
        SyncController clockedController = new SyncController(clockedService);

        stubMembership();
        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenAnswer(invocation -> {
            clock.set(start.plusSeconds(5));
            return 100L;
        });

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)), 50, null);

        ResponseEntity<SyncResponse> response = clockedController.syncDelta(principal, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().serverTime())
                .as("serverTime must be the instant the request started with, not a later one")
                .isEqualTo(start);
    }

    @Test
    void syncDeltaReactionPageTruncatesSetsHasMoreAndCursor() {
        Instant since = Instant.now().minusSeconds(3600);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        SyncProperties props = new SyncProperties();
        props.setMaxReactionUpdates(2);
        MessageService capped = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                reactionRepository,
                reactionEventRepository,
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                mock(ApplicationEventPublisher.class),
                props
        );
        SyncController controller = new SyncController(capped);

        stubMembership();
        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());
        when(reactionEventRepository.findEventIdsSince(eq(conversationId), eq(since), any(), eq(3)))
                .thenReturn(List.of(
                        new ReactionEventIdRow(10L, first),
                        new ReactionEventIdRow(11L, second),
                        new ReactionEventIdRow(12L, third)));
        when(reactionRepository.findRowsByMessageIdIn(any()))
                .thenReturn(List.of(new ReactionRow(first, userId, "👍"),
                        new ReactionRow(second, userId, "❤️")));
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)), 50, since);

        SyncResponse.ConversationDelta delta = controller.syncDelta(principal, request).getBody().deltas().get(0);

        assertThat(delta.reactionUpdates()).hasSize(2);
        assertThat(delta.reactionUpdates()).extracting(ReactionUpdatedPayload::messageId)
                .containsExactly(first, second);
        assertThat(delta.reactionCursor()).isEqualTo(11L);
        assertThat(delta.hasMore()).isTrue();
        assertThat(delta.reactionsFullRefresh()).isFalse();
    }

    @Test
    void syncDeltaSinceOlderThanRetentionReturnsFullRefresh() {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(40));
        SyncProperties props = new SyncProperties();
        props.setReactionRetentionDays(30);
        MessageService service = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                reactionRepository,
                reactionEventRepository,
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                mock(ApplicationEventPublisher.class),
                props
        );
        SyncController controller = new SyncController(service);

        stubMembership();
        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L)), 50, since);

        SyncResponse.ConversationDelta delta = controller.syncDelta(principal, request).getBody().deltas().get(0);

        assertThat(delta.reactionsFullRefresh()).isTrue();
        assertThat(delta.reactionUpdates()).isEmpty();
        assertThat(delta.reactionCursor()).isNull();
        org.mockito.Mockito.verifyNoInteractions(reactionEventRepository);
    }

    @Test
    void syncDeltaEchoesReactionCursorIntoTheEventQuery() {
        Instant since = Instant.now().minusSeconds(3600);
        UUID reactionMsgId = UUID.randomUUID();
        stubMembership();
        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());
        when(reactionEventRepository.findEventIdsSince(eq(conversationId), eq(since), eq(42L), anyInt()))
                .thenReturn(List.of(new ReactionEventIdRow(43L, reactionMsgId)));
        when(reactionRepository.findRowsByMessageIdIn(any()))
                .thenReturn(List.of(new ReactionRow(reactionMsgId, userId, "👍")));
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);

        SyncRequest request = new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(conversationId, 100L, 42L)), 50, since);

        SyncResponse.ConversationDelta delta = syncController.syncDelta(principal, request).getBody().deltas().get(0);

        assertThat(delta.reactionUpdates()).hasSize(1);
        assertThat(delta.reactionCursor()).isNull();
        assertThat(delta.hasMore()).isFalse();
    }

    @Test
    void syncDeltaOmitsMutedUntilWhenTheMuteIsNotEffective() throws Exception {
        ObjectMapper mapper = mapper();
        SyncResponse.ConversationDelta delta = new SyncResponse.ConversationDelta(
                conversationId, List.of(), List.of(), List.of(), List.of(),
                0L, false, null, false, false, null, false, false, null);

        JsonNode json = mapper.valueToTree(delta);

        assertThat(json.has("mutedUntil")).isFalse();
        assertThat(json.get("muted").asBoolean()).isFalse();
        assertThat(json.get("archived").asBoolean()).isFalse();
        assertThat(json.get("pinned").asBoolean()).isFalse();
        assertThat(json.has("pinnedAt")).isFalse();
    }

    @Test
    void syncDeltaSerializesMutedUntilWhenMuted() throws Exception {
        ObjectMapper mapper = mapper();
        Instant until = Instant.parse("2026-12-09T10:00:00Z");
        SyncResponse.ConversationDelta delta = new SyncResponse.ConversationDelta(
                conversationId, List.of(), List.of(), List.of(), List.of(),
                0L, false, null, false, true, until, true, true, until);

        JsonNode json = mapper.valueToTree(delta);

        assertThat(json.get("mutedUntil").asText()).isEqualTo("2026-12-09T10:00:00Z");
        assertThat(json.get("muted").asBoolean()).isTrue();
        assertThat(json.get("archived").asBoolean()).isTrue();
        assertThat(json.get("pinned").asBoolean()).isTrue();
        assertThat(json.get("pinnedAt").asText()).isEqualTo("2026-12-09T10:00:00Z");
    }

    /**
     * Matches the server's Spring Boot ObjectMapper: JavaTimeModule registered
     * and {@code WRITE_DATES_AS_TIMESTAMPS} disabled, so {@code Instant}
     * renders as an ISO-8601 string.
     */
    private void stubMembership() {
        when(memberRepository.findMemberFlags(eq(userId), any())).thenReturn(
                List.of(new MemberFlagsRow(conversationId, false, null, false, false, null)));
    }

    private SyncRequest v2Request(long changeCursor, Long generation) {
        return new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(
                        conversationId, 0L, null, changeCursor, generation)),
                50, null, 2);
    }

    private MessageRow v2Row(UUID id, long changeVersion, long generation) {
        return new MessageRow(id, conversationId, changeVersion, userId, "Test User",
                Message.Type.TEXT, "page-row-" + changeVersion, UUID.randomUUID(), null,
                Instant.parse("2026-11-12T00:00:00Z"), null, null, false,
                changeVersion, generation);
    }

    private static void assertResetOnly(SyncResponse.ConversationDelta delta,
                                        long upperBound, long generation) {
        assertThat(delta.reset()).isTrue();
        assertThat(delta.changes()).isEmpty();
        assertThat(delta.changeCursor()).isZero();
        assertThat(delta.changeUpperBound()).isEqualTo(upperBound);
        assertThat(delta.changeHasMore()).isFalse();
        assertThat(delta.generation()).isEqualTo(generation);
        assertThat(delta.reactionUpdates()).isEmpty();
        assertThat(delta.reactionCursor()).isNull();
        assertThat(delta.hasMore()).isFalse();
        assertThat(delta.reactionsFullRefresh()).isFalse();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private record ReactionEventIdRow(Long id, UUID messageId) implements ReactionEventId {
        @Override
        public Long getId() {
            return id;
        }

        @Override
        public UUID getMessageId() {
            return messageId;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant start) {
            this.current = start;
        }

        private void set(Instant next) {
            this.current = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
