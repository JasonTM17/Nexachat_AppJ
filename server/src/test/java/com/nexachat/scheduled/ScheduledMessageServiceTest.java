package com.nexachat.scheduled;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.scheduled.dto.CreateScheduledMessageRequest;
import com.nexachat.scheduled.dto.UpdateScheduledMessageRequest;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link ScheduledMessageService} — the REST layer is
 * covered end-to-end by {@code ScheduledMessageFlowIT}; this class pins the
 * invariants that do not need a database: clamping, replay statuses, the
 * pending ceiling, the E2EE refusal and the cancel guard outcomes.
 */
class ScheduledMessageServiceTest {

    private final ConversationRepository conversationRepository =
            mock(ConversationRepository.class);
    private final ConversationMemberRepository memberRepository =
            mock(ConversationMemberRepository.class);
    private final ScheduledMessageRepository scheduledMessageRepository =
            mock(ScheduledMessageRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher eventPublisher =
            mock(ApplicationEventPublisher.class);

    private final ScheduledMessageService service = new ScheduledMessageService(
            conversationRepository, memberRepository, scheduledMessageRepository,
            userRepository, eventPublisher, 60L);

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Conversation conversation = mock(Conversation.class);

    @BeforeEach
    void memberAndConversationExist() {
        when(conversation.getId()).thenReturn(conversationId);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        when(scheduledMessageRepository.findBySenderAndClientMessageId(eq(userId), any()))
                .thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------- create

    @Test
    void createStoresTheRowAndEchoesTheClampedSendAt() {
        Instant tooSoon = Instant.now().plusSeconds(5);
        when(scheduledMessageRepository.countPending(conversationId, userId)).thenReturn(0L);
        when(scheduledMessageRepository.insertIgnoringDuplicate(
                any(), eq(conversationId), eq(userId), any(), any(), any()))
                .thenReturn(1);
        when(scheduledMessageRepository.findById(any()))
                .thenAnswer(invocation -> Optional.of(
                        row(conversation, userId,
                                invocation.<UUID>getArgument(0),
                                Instant.now().plusSeconds(60))));

        var result = service.create(userId, conversationId,
                new CreateScheduledMessageRequest(UUID.randomUUID(), "hi", tooSoon));

        assertThat(result.created()).isTrue();
        // The echo must be the clamped floor, not the five-second input.
        assertThat(result.scheduledMessage().sendAt())
                .isAfter(tooSoon)
                .isBefore(Instant.now().plus(2, ChronoUnit.MINUTES));
        verify(eventPublisher).publishEvent(any(ScheduledMessageChangedEvent.class));
    }

    @Test
    void createClampsASendAtBeyondOneYearDown() {
        Instant tooFar = Instant.now().plus(400, ChronoUnit.DAYS);
        when(scheduledMessageRepository.countPending(conversationId, userId)).thenReturn(0L);
        when(scheduledMessageRepository.insertIgnoringDuplicate(
                any(), eq(conversationId), eq(userId), any(), any(), any()))
                .thenReturn(1);
        Instant ceilingEcho = Instant.now().plus(365, ChronoUnit.DAYS);
        when(scheduledMessageRepository.findById(any()))
                .thenAnswer(invocation -> Optional.of(
                        row(conversation, userId,
                                invocation.<UUID>getArgument(0), ceilingEcho)));

        var result = service.create(userId, conversationId,
                new CreateScheduledMessageRequest(UUID.randomUUID(), "hi", tooFar));

        assertThat(result.created()).isTrue();
        assertThat(result.scheduledMessage().sendAt())
                .isBefore(tooFar)
                .isBefore(Instant.now().plus(366, ChronoUnit.DAYS));
    }

    @Test
    void replayOfAPendingKeyReturnsTheStoredRowWithoutCreating() {
        ScheduledMessage stored = row(conversation, userId, UUID.randomUUID(),
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findBySenderAndClientMessageId(
                userId, stored.getClientMessageId()))
                .thenReturn(Optional.of(stored));

        var result = service.create(userId, conversationId,
                new CreateScheduledMessageRequest(
                        stored.getClientMessageId(), "different body", Instant.now()));

        assertThat(result.created()).isFalse();
        assertThat(result.scheduledMessage().id()).isEqualTo(stored.getId());
        verify(scheduledMessageRepository, never())
                .insertIgnoringDuplicate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayOfATerminalKeyIsRejectedWithTheStoredStatus() {
        ScheduledMessage stored = row(conversation, userId, UUID.randomUUID(),
                Instant.now().plusSeconds(3600));
        stored.markFailed("NOT_MEMBER", Instant.now());
        when(scheduledMessageRepository.findBySenderAndClientMessageId(
                userId, stored.getClientMessageId()))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(
                        stored.getClientMessageId(), "hi", Instant.now())))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.SCHEDULED_INVALID);
                    assertThat(exception.getDetails()).containsEntry("status", "FAILED");
                });
    }

    @Test
    void replayOfAKeyStoredInAnotherConversationIsAValidationError() {
        UUID otherConversation = UUID.randomUUID();
        Conversation other = mock(Conversation.class);
        when(other.getId()).thenReturn(otherConversation);
        ScheduledMessage stored = row(other, userId, UUID.randomUUID(),
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findBySenderAndClientMessageId(
                userId, stored.getClientMessageId()))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(
                        stored.getClientMessageId(), "hi", Instant.now())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    @Test
    void createAtThePendingCeilingIsRejected() {
        when(scheduledMessageRepository.countPending(conversationId, userId)).thenReturn(50L);

        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(
                        UUID.randomUUID(), "hi", Instant.now().plusSeconds(3600))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.SCHEDULED_LIMIT);
                    assertThat(exception.getDetails()).containsEntry("limit", 50);
                });
        verify(scheduledMessageRepository, never())
                .insertIgnoringDuplicate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createIntoAnE2eeConversationUsesTheSendPathRefusal() {
        when(conversation.isE2eeEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(
                        UUID.randomUUID(), "hi", Instant.now().plusSeconds(3600))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.E2EE_REQUIRED));
    }

    @Test
    void nonMemberCannotCreateListOrCancel() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);

        CreateScheduledMessageRequest request = new CreateScheduledMessageRequest(
                UUID.randomUUID(), "hi", Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.create(userId, conversationId, request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER));
        assertThatThrownBy(() -> service.list(userId, conversationId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER));
        assertThatThrownBy(() -> service.cancel(userId, conversationId, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER));
    }

    @Test
    void bodyValidationMirrorsTheTextSendContract() {
        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(UUID.randomUUID(), "   ",
                        Instant.now().plusSeconds(3600))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(UUID.randomUUID(), "x" + ' ',
                        Instant.now().plusSeconds(3600))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(userId, conversationId,
                new CreateScheduledMessageRequest(UUID.randomUUID(), "x".repeat(10_001),
                        Instant.now().plusSeconds(3600))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelOfAnUnclaimedPendingRowSucceeds() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.cancelIfCancelable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong()))
                .thenReturn(1);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(3600));
        stored.cancel(null, Instant.now());
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        service.cancel(userId, conversationId, scheduledId);

        verify(scheduledMessageRepository).cancelIfCancelable(
                scheduledId, userId, conversationId, 60L);
        verify(eventPublisher).publishEvent(any(ScheduledMessageChangedEvent.class));
    }

    @Test
    void cancelOfAClaimedRowIsDispatching() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.cancelIfCancelable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.cancel(userId, conversationId, scheduledId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.DISPATCHING);
                    assertThat(exception.getDetails()).containsEntry("status", "PENDING");
                });
    }

    @Test
    void cancelOfATerminalRowIsDispatchingWithItsStatus() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.cancelIfCancelable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(3600));
        stored.markSent(UUID.randomUUID(), Instant.now());
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.cancel(userId, conversationId, scheduledId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.DISPATCHING);
                    assertThat(exception.getDetails()).containsEntry("status", "SENT");
                });
    }

    @Test
    void cancelOfSomeoneElsesRowIsNotFound() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.cancelIfCancelable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, UUID.randomUUID(), scheduledId,
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.cancel(userId, conversationId, scheduledId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateOfAnUnclaimedPendingRowEchoesTheStoredValues() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.updateIfEditable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong(),
                any(), any(), anyBoolean()))
                .thenReturn(1);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(7200));
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        var response = service.update(userId, conversationId, scheduledId,
                new UpdateScheduledMessageRequest("new body",
                        Instant.now().plusSeconds(7200)));

        assertThat(response.id()).isEqualTo(scheduledId);
        verify(scheduledMessageRepository).updateIfEditable(
                eq(scheduledId), eq(userId), eq(conversationId), eq(60L),
                eq("new body"), any(), eq(true));
        verify(eventPublisher).publishEvent(any(ScheduledMessageChangedEvent.class));
    }

    @Test
    void updateWithoutBodyOrSendAtIsRejected() {
        assertThatThrownBy(() -> service.update(userId, conversationId,
                UUID.randomUUID(), new UpdateScheduledMessageRequest(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verify(scheduledMessageRepository, never())
                .updateIfEditable(any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void updateValidatesBodyTheSameWayAsCreate() {
        UUID scheduledId = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(userId, conversationId, scheduledId,
                new UpdateScheduledMessageRequest("   ", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verify(scheduledMessageRepository, never())
                .updateIfEditable(any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void updateOfAClaimedRowIsDispatching() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.updateIfEditable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong(),
                any(), any(), anyBoolean()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(userId, conversationId, scheduledId,
                new UpdateScheduledMessageRequest(null,
                        Instant.now().plusSeconds(7200))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.DISPATCHING);
                    assertThat(exception.getDetails()).containsEntry("status", "PENDING");
                });
    }

    @Test
    void updateOfATerminalRowIsDispatchingWithItsStatus() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.updateIfEditable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong(),
                any(), any(), anyBoolean()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, userId, scheduledId,
                Instant.now().plusSeconds(3600));
        stored.markSent(UUID.randomUUID(), Instant.now());
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(userId, conversationId, scheduledId,
                new UpdateScheduledMessageRequest("new body", null)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.DISPATCHING);
                    assertThat(exception.getDetails()).containsEntry("status", "SENT");
                });
    }

    @Test
    void updateOfSomeoneElsesRowIsNotFound() {
        UUID scheduledId = UUID.randomUUID();
        when(scheduledMessageRepository.updateIfEditable(
                eq(scheduledId), eq(userId), eq(conversationId), anyLong(),
                any(), any(), anyBoolean()))
                .thenReturn(0);
        ScheduledMessage stored = row(conversation, UUID.randomUUID(), scheduledId,
                Instant.now().plusSeconds(3600));
        when(scheduledMessageRepository.findById(scheduledId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(userId, conversationId, scheduledId,
                new UpdateScheduledMessageRequest("new body", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    @Test
    void updateIsRefusedForE2eeConversations() {
        when(conversation.isE2eeEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.update(userId, conversationId,
                UUID.randomUUID(),
                new UpdateScheduledMessageRequest("new body", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.E2EE_REQUIRED));
        verify(scheduledMessageRepository, never())
                .updateIfEditable(any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void nonMemberCannotUpdate() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update(userId, conversationId,
                UUID.randomUUID(),
                new UpdateScheduledMessageRequest("new body", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER));
        verify(scheduledMessageRepository, never())
                .updateIfEditable(any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    // ---------------------------------------------------------------- helpers

    private ScheduledMessage row(Conversation conv, UUID senderId, UUID id, Instant sendAt) {
        User sender = mock(User.class);
        when(sender.getId()).thenReturn(senderId);
        ScheduledMessage row = ScheduledMessage.create(
                conv, sender, UUID.randomUUID(), "body", sendAt);
        if (id != null) {
            org.springframework.test.util.ReflectionTestUtils
                    .setField(row, "id", id);
        }
        return row;
    }
}
