package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.PinRequest;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link PinService}: the DIRECT/GROUP pin policy, the
 * ≤3 ceiling, cross-conversation rejection, and idempotent pin/unpin
 * semantics — all against the locked conversation row.
 */
@ExtendWith(MockitoExtension.class)
class PinServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private PinnedMessageRepository pinRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PinService pinService;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pinService = new PinService(
                conversationRepository, memberRepository, messageRepository,
                pinRepository, messageService, eventPublisher);
    }

    // ------------------------------------------------------------ fixtures

    private Conversation conversation(Conversation.Type type) {
        User owner = User.create("+84921112233", "hash", "Owner");
        ReflectionTestUtils.setField(owner, "id", userId);
        Conversation conversation = type == Conversation.Type.GROUP
                ? Conversation.group(owner, "Team")
                : Conversation.direct(owner, "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        return conversation;
    }

    private ConversationMember member(Conversation conversation, ConversationMember.Role role) {
        User user = User.create("+84921112233", "hash", "Member");
        ReflectionTestUtils.setField(user, "id", userId);
        return ConversationMember.join(conversation, user, role);
    }

    private Message messageIn(UUID convId) {
        Message message;
        try {
            var ctor = Message.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            message = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(message, "id", messageId);
        Conversation conversation = conversation(Conversation.Type.DIRECT);
        ReflectionTestUtils.setField(conversation, "id", convId);
        ReflectionTestUtils.setField(message, "conversation", conversation);
        return message;
    }

    private void directConversationWith(ConversationMember.Role role) {
        Conversation conversation = conversation(Conversation.Type.DIRECT);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member(conversation, role)));
    }

    private void groupConversationWith(ConversationMember.Role role) {
        Conversation conversation = conversation(Conversation.Type.GROUP);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member(conversation, role)));
    }

    private void messageFound() {
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(messageIn(conversationId)));
    }

    private PinRequest request() {
        return new PinRequest(messageId);
    }

    // -------------------------------------------------------------------- pin

    @Test
    void pin_directMember_pinsAndPublishes() {
        directConversationWith(ConversationMember.Role.MEMBER);
        messageFound();
        when(pinRepository.existsByConversation_IdAndMessage_Id(conversationId, messageId))
                .thenReturn(false);
        when(pinRepository.countByConversation_Id(conversationId)).thenReturn(1L);
        when(pinRepository.insertIgnoringConflict(conversationId, messageId, userId)).thenReturn(1);

        pinService.pin(userId, conversationId, request());

        verify(pinRepository).insertIgnoringConflict(conversationId, messageId, userId);
        ArgumentCaptor<PinUpdatedEvent> event = ArgumentCaptor.forClass(PinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().payload().messageId()).isEqualTo(messageId);
        assertThat(event.getValue().payload().pinned()).isTrue();
    }

    @Test
    void pin_groupMember_throwsNotAdmin() {
        groupConversationWith(ConversationMember.Role.MEMBER);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(pinRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void pin_groupAdmin_pins() {
        groupConversationWith(ConversationMember.Role.ADMIN);
        messageFound();
        when(pinRepository.existsByConversation_IdAndMessage_Id(conversationId, messageId))
                .thenReturn(false);
        when(pinRepository.countByConversation_Id(conversationId)).thenReturn(0L);
        when(pinRepository.insertIgnoringConflict(conversationId, messageId, userId)).thenReturn(1);

        pinService.pin(userId, conversationId, request());

        verify(pinRepository).insertIgnoringConflict(conversationId, messageId, userId);
        verify(eventPublisher).publishEvent(any(PinUpdatedEvent.class));
    }

    @Test
    void pin_fourthPin_throwsPinLimit() {
        directConversationWith(ConversationMember.Role.MEMBER);
        messageFound();
        when(pinRepository.existsByConversation_IdAndMessage_Id(conversationId, messageId))
                .thenReturn(false);
        when(pinRepository.countByConversation_Id(conversationId))
                .thenReturn((long) PinService.MAX_PINS_PER_CONVERSATION);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.PIN_LIMIT);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(pinRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void pin_alreadyPinned_isIdempotentNoop() {
        directConversationWith(ConversationMember.Role.MEMBER);
        messageFound();
        when(pinRepository.existsByConversation_IdAndMessage_Id(conversationId, messageId))
                .thenReturn(true);

        pinService.pin(userId, conversationId, request());

        // No limit check and no write — but the truthful frame still goes out.
        verify(pinRepository, never()).countByConversation_Id(any());
        verify(pinRepository, never()).insertIgnoringConflict(any(), any(), any());
        verify(eventPublisher).publishEvent(any(PinUpdatedEvent.class));
    }

    @Test
    void pin_messageInOtherConversation_throwsValidation() {
        directConversationWith(ConversationMember.Role.MEMBER);
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(messageIn(UUID.randomUUID())));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(pinRepository, never()).insertIgnoringConflict(any(), any(), any());
    }

    @Test
    void pin_messageMissing_throwsMessageNotFound() {
        directConversationWith(ConversationMember.Role.MEMBER);
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void pin_notMember_throwsNotMember() {
        // Member check runs before the conversation lock — no lock stub.
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void pin_conversationMissing_throwsNotMember() {
        // A missing conversation has no member rows either, so the
        // identity-first ordering denies with NOT_MEMBER before the
        // CONVERSATION_NOT_FOUND lock path is ever reached.
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.pin(userId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    // ------------------------------------------------------------------ unpin

    @Test
    void unpin_member_removesAndPublishesFalse() {
        directConversationWith(ConversationMember.Role.MEMBER);
        messageFound();
        when(pinRepository.deleteOne(conversationId, messageId)).thenReturn(1);

        pinService.unpin(userId, conversationId, messageId);

        ArgumentCaptor<PinUpdatedEvent> event = ArgumentCaptor.forClass(PinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().payload().messageId()).isEqualTo(messageId);
        assertThat(event.getValue().payload().pinned()).isFalse();
    }

    @Test
    void unpin_groupMember_throwsNotAdmin() {
        groupConversationWith(ConversationMember.Role.MEMBER);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> pinService.unpin(userId, conversationId, messageId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        verify(pinRepository, never()).deleteOne(any(), any());
    }
}
