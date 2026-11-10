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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

        SyncResponse.ConversationDelta delta = response.getBody().deltas().get(0);
        assertThat(delta.conversationId()).isEqualTo(conversationId);
        assertThat(delta.newMessages()).hasSize(1);
        assertThat(delta.newMessages().get(0).seq()).isEqualTo(101L);
        assertThat(delta.maxSeq()).isEqualTo(105L);
        assertThat(delta.hasMore()).isFalse();
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
