package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.ReactRequest;
import com.nexachat.message.dto.ReactionRow;
import com.nexachat.message.dto.ReactionSlice;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ReactionService}: the toggle contract
 * (delete-then-insert, never check-then-act), member enforcement, emoji
 * validation, and the post-toggle aggregate carried by the event.
 */
@ExtendWith(MockitoExtension.class)
class ReactionServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageReactionRepository reactionRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReactionService reactionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reactionService = new ReactionService(
                messageRepository, reactionRepository, memberRepository, eventPublisher);
    }

    private Message messageInConversation() {
        User user = User.create("+84921112233", "hash", "Sender");
        ReflectionTestUtils.setField(user, "id", userId);
        Conversation conversation = Conversation.direct(user, "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        Message message = newMessageWithId(messageId);
        ReflectionTestUtils.setField(message, "conversation", conversation);
        return message;
    }

    private static Message newMessageWithId(UUID id) {
        Message message;
        try {
            var ctor = Message.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            message = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private void memberMessage() {
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(messageInConversation()));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
    }

    @Test
    void toggle_noExisting_insertsAndPublishesAggregate() {
        memberMessage();
        when(reactionRepository.deleteOne(messageId, userId, "👍")).thenReturn(0);
        when(reactionRepository.insertIgnoringConflict(messageId, userId, "👍")).thenReturn(1);
        when(reactionRepository.findRowsByMessageIdIn(List.of(messageId))).thenReturn(List.of(
                new ReactionRow(messageId, userId, "👍"),
                new ReactionRow(messageId, otherId, "👍"),
                new ReactionRow(messageId, otherId, "❤️")));

        reactionService.toggle(userId, messageId, new ReactRequest("👍"));

        verify(reactionRepository).insertIgnoringConflict(messageId, userId, "👍");
        ArgumentCaptor<ReactionUpdatedEvent> event =
                ArgumentCaptor.forClass(ReactionUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().payload().messageId()).isEqualTo(messageId);
        assertThat(event.getValue().payload().reactions()).containsExactly(
                new ReactionSlice("❤️", 1, List.of(otherId)),
                new ReactionSlice("👍", 2, List.of(userId, otherId)));
    }

    @Test
    void toggle_existing_removesAndNeverInserts() {
        memberMessage();
        when(reactionRepository.deleteOne(messageId, userId, "👍")).thenReturn(1);
        when(reactionRepository.findRowsByMessageIdIn(List.of(messageId)))
                .thenReturn(List.of()); // post-state: nothing left

        reactionService.toggle(userId, messageId, new ReactRequest("👍"));

        verify(reactionRepository, never()).insertIgnoringConflict(any(), any(), any());
        ArgumentCaptor<ReactionUpdatedEvent> event =
                ArgumentCaptor.forClass(ReactionUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().payload().reactions()).isEmpty();
    }

    @Test
    void toggle_messageMissing_throwsMessageNotFound() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reactionService.toggle(userId, messageId, new ReactRequest("👍")));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(reactionRepository, never()).deleteOne(any(), any(), any());
    }

    @Test
    void toggle_notMember_throwsNotMember() {
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(messageInConversation()));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reactionService.toggle(userId, messageId, new ReactRequest("👍")));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(reactionRepository, never()).deleteOne(any(), any(), any());
    }

    @Test
    void toggle_blankOrMissingEmoji_throwsValidation() {
        for (ReactRequest bad : new ReactRequest[] {
                new ReactRequest(null), new ReactRequest(""), new ReactRequest("   ")}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> reactionService.toggle(userId, messageId, bad));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reactionService.toggle(userId, messageId, null));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).findById(any());
    }

    @Test
    void toggle_tooLongEmoji_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reactionService.toggle(userId, messageId,
                        new ReactRequest("👍".repeat(17))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).findById(any());
    }

    @Test
    void toggle_emojiBoundary_sixteenCodepointsAccepted() {
        memberMessage();
        String maxEmoji = "👍".repeat(16); // 16 code points — VARCHAR(16) fits
        when(reactionRepository.deleteOne(messageId, userId, maxEmoji)).thenReturn(0);
        when(reactionRepository.insertIgnoringConflict(messageId, userId, maxEmoji)).thenReturn(1);
        when(reactionRepository.findRowsByMessageIdIn(List.of(messageId))).thenReturn(List.of());

        reactionService.toggle(userId, messageId, new ReactRequest(maxEmoji));

        verify(reactionRepository).insertIgnoringConflict(messageId, userId, maxEmoji);
    }
}
