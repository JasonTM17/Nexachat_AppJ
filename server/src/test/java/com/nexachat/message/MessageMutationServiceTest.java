package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.MessageDeletedPayload;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageMutationServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MessageAttachmentRepository attachmentRepository;
    @Mock private MessageReactionRepository reactionRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationMemberRepository memberRepository;
    @Mock private PinnedMessageRepository pinnedMessageRepository;
    @Mock private StorageService storageService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private MessageService service;
    private User author;
    private Conversation conversation;
    private Message message;

    @BeforeEach
    void setUp() {
        service = new MessageService(messageRepository, attachmentRepository, reactionRepository,
                mock(ReactionEventRepository.class), conversationRepository, memberRepository,
                pinnedMessageRepository, storageService, eventPublisher, new SyncProperties());
        author = user(userId, "Author");
        conversation = Conversation.direct(author, "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        message = message(conversation, author, Message.Type.TEXT, "before");
        ReflectionTestUtils.setField(message, "id", messageId);
        ReflectionTestUtils.setField(message, "seq", 17L);
        ReflectionTestUtils.setField(message, "clientMessageId", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", Instant.now().minusSeconds(60));
        lenient().when(memberRepository.existsByConversation_IdAndUser_Id(any(), any())).thenReturn(true);
        lenient().when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));
        lenient().when(conversationRepository.findByIdForUpdate(any()))
                .thenReturn(Optional.of(conversation));
        lenient().when(conversationRepository.incrementChangeVersion(any())).thenReturn(1);
        lenient().when(conversationRepository.findChangeVersion(any())).thenReturn(1L);
        lenient().when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        lenient().when(messageRepository.findRowById(messageId)).thenAnswer(ignored -> Optional.of(row(message)));
        lenient().when(attachmentRepository.findByMessage_IdIn(any())).thenReturn(List.of());
        lenient().when(reactionRepository.findRowsByMessageIdIn(any())).thenReturn(List.of());
        lenient().when(messageRepository.findReplyRefsByIdIn(any())).thenReturn(List.of());
        lenient().when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
    }

    @Test
    void editTrimsContentAndPublishesOneUpdatedEvent() {
        var response = service.edit(userId, conversationId, messageId, "  after  ");

        assertThat(response.content()).isEqualTo("after");
        assertThat(response.editedAt()).isNotNull();
        assertThat(message.getContent()).isEqualTo("after");
        assertThat(message.getChangeVersion()).isEqualTo(1L);
        verify(conversationRepository).incrementChangeVersion(conversationId);
        verify(eventPublisher).publishEvent(any(MessageUpdatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(MessageDeletedEvent.class));
    }

    @Test
    void editNonMemberGetsForbiddenBeforeMessageLookup() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);

        ApiException error = editError(userId, conversationId, "next");

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(messageRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void editWrongConversationIsHiddenAsNotFound() {
        UUID wrongConversationId = UUID.randomUUID();

        ApiException error = editError(userId, wrongConversationId, "next");

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void editByNonAuthorIsForbidden() {
        ApiException error = editError(otherUserId, conversationId, "next");

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.NOT_MESSAGE_AUTHOR);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void editDeletedMessageReturnsConflict() {
        ReflectionTestUtils.setField(message, "deletedAt", Instant.parse("2026-11-05T11:00:00Z"));

        ApiException error = editError(userId, conversationId, "next");

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.MESSAGE_DELETED);
    }

    @Test
    void editRejectsNonTextBlankAndOverlongContent() {
        ReflectionTestUtils.setField(message, "type", Message.Type.FILE);
        assertThat(editError(userId, conversationId, "next").getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);

        ReflectionTestUtils.setField(message, "type", Message.Type.TEXT);
        assertThat(editError(userId, conversationId, "  \n").getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(editError(userId, conversationId, "x".repeat(10_001)).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void identicalEditDoesNotChangeTimestampOrPublish() {
        Instant editedAt = Instant.parse("2026-11-05T11:00:00Z");
        ReflectionTestUtils.setField(message, "content", "same");
        ReflectionTestUtils.setField(message, "editedAt", editedAt);
        when(messageRepository.findRowById(messageId)).thenReturn(Optional.of(row(message)));

        var response = service.edit(userId, conversationId, messageId, " same ");

        assertThat(response.content()).isEqualTo("same");
        assertThat(response.editedAt()).isEqualTo(editedAt);
        assertThat(message.getChangeVersion()).isZero();
        verify(conversationRepository, never()).incrementChangeVersion(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deleteIsAuthorOnlyIdempotentAndPublishesOnce() {
        service.delete(userId, conversationId, messageId);
        Instant deletedAt = message.getDeletedAt();
        service.delete(userId, conversationId, messageId);

        assertThat(deletedAt).isNotNull();
        assertThat(message.getContent()).isNull();
        assertThat(message.getChangeVersion()).isEqualTo(1L);
        verify(conversationRepository, times(1)).incrementChangeVersion(conversationId);
        verify(reactionRepository).deleteByMessageId(messageId);
        ArgumentCaptor<MessageDeletedEvent> deleted = ArgumentCaptor.forClass(MessageDeletedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(deleted.capture());
        MessageDeletedPayload payload = deleted.getValue().payload();
        assertThat(payload.changeVersion()).isEqualTo(1L);
        assertThat(payload.generation()).isEqualTo(1L);
        assertThat(payload.deletedAt()).isEqualTo(deletedAt);
        verify(eventPublisher, never()).publishEvent(any(MessageUpdatedEvent.class));
    }

    @Test
    void deleteUsesInjectedClockAndAllowsRecallAtTheExactBoundary() {
        Instant boundary = message.getCreatedAt().plus(MessageRecallProperties.DEFAULT_RECALL_WINDOW);
        service = new MessageService(messageRepository, null, attachmentRepository, reactionRepository,
                mock(ReactionEventRepository.class), conversationRepository, memberRepository,
                pinnedMessageRepository, storageService, eventPublisher, new SyncProperties(),
                Clock.fixed(boundary, ZoneOffset.UTC), new MessageRecallProperties());

        service.delete(userId, conversationId, messageId);

        assertThat(message.getDeletedAt()).isEqualTo(boundary.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void deleteByNonAuthorIsForbidden() {
        ApiException error = catchThrowableOfType(ApiException.class,
                () -> service.delete(otherUserId, conversationId, messageId));

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.NOT_MESSAGE_AUTHOR);
        verify(conversationRepository, never()).incrementChangeVersion(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deleteRemovesPinAndPublishesPinUpdated() {
        when(pinnedMessageRepository.deleteOne(conversationId, messageId)).thenReturn(1);

        service.delete(userId, conversationId, messageId);

        verify(pinnedMessageRepository).deleteOne(conversationId, messageId);
        verify(eventPublisher).publishEvent(any(PinUpdatedEvent.class));
    }

    @Test
    void deletedRowsAreSerializedAsTombstonesEvenIfOldContentAndInteractionsRemain() {
        MessageRow raw = new MessageRow(messageId, conversationId, 17L, userId, "Author",
                Message.Type.TEXT, "must not leak", UUID.randomUUID(), null,
                Instant.parse("2026-11-05T10:00:00Z"), null,
                Instant.parse("2026-11-05T11:00:00Z"));
        when(attachmentRepository.findByMessage_IdIn(List.of(messageId))).thenReturn(List.of(
                attachment(userId, message)));
        when(reactionRepository.findRowsByMessageIdIn(List.of(messageId))).thenReturn(List.of(
                new com.nexachat.message.dto.ReactionRow(messageId, otherUserId, "👍")));

        var response = service.toResponses(List.of(raw), otherUserId).get(0);

        assertThat(response.seq()).isEqualTo(17L);
        assertThat(response.content()).isNull();
        assertThat(response.deletedAt()).isNotNull();
        assertThat(response.attachments()).isEmpty();
        assertThat(response.reactions()).isEmpty();
    }

    private ApiException editError(UUID callerId, UUID routeConversationId, String content) {
        if (!routeConversationId.equals(conversationId)) {
            when(memberRepository.existsByConversation_IdAndUser_Id(routeConversationId, callerId)).thenReturn(true);
        }
        return catchThrowableOfType(ApiException.class,
                () -> service.edit(callerId, routeConversationId, messageId, content));
    }

    private MessageRow row(Message value) {
        return new MessageRow(messageId, conversationId, 17L, value.getSender().getId(), "Author",
                value.getType(), value.getContent(), value.getClientMessageId(), null,
                value.getCreatedAt(), value.getEditedAt(), value.getDeletedAt(),
                false, value.getChangeVersion(), 1L);
    }

    private static User user(UUID id, String name) {
        User user = User.create("+84901112233", "hash", name);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Message message(Conversation conversation, User sender,
                                   Message.Type type, String content) {
        Message message;
        try {
            var constructor = Message.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            message = constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        ReflectionTestUtils.setField(message, "conversation", conversation);
        ReflectionTestUtils.setField(message, "sender", sender);
        ReflectionTestUtils.setField(message, "type", type);
        ReflectionTestUtils.setField(message, "content", content);
        return message;
    }

    private static MessageAttachment attachment(UUID ownerId, Message message) {
        MessageAttachment attachment = MessageAttachment.upload(
                user(ownerId, "Owner"), "attachments/test.png", "test.png", "image/png", 5,
                null, null, null, null);
        ReflectionTestUtils.setField(attachment, "id", UUID.randomUUID());
        attachment.bindTo(message);
        return attachment;
    }
}
