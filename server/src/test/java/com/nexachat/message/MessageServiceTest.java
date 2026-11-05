package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.MessagePageResponse;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageAttachmentRepository attachmentRepository;
    @Mock
    private MessageReactionRepository reactionRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MessageService messageService;

    private final UUID senderId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID clientMessageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, attachmentRepository, reactionRepository,
                conversationRepository, memberRepository, storageService, eventPublisher);
        lenient().when(attachmentRepository.findByMessage_IdIn(any())).thenReturn(List.of());
        lenient().when(reactionRepository.findRowsByMessageIdIn(any())).thenReturn(List.of());
        lenient().when(messageRepository.findReplyRefsByIdIn(any())).thenReturn(List.of());
        lenient().when(storageService.resolveUrl(anyString())).thenAnswer(i -> "/api/v1/files/" + i.getArgument(0));
    }

    private MessageRow row(long seq) {
        return new MessageRow(
                UUID.randomUUID(), conversationId, seq, senderId, "Sender Name",
                Message.Type.TEXT, "hello", clientMessageId, null, Instant.now(), null, null);
    }

    private SendMessageRequest request() {
        return new SendMessageRequest(clientMessageId, "TEXT", "hello", null, List.of());
    }

    private void memberOk() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId)).thenReturn(true);
    }

    // -------------------------------------------------------------------- send

    @Test
    void send_happy_inserts_advancesAndPublishesEvent() {
        memberOk();
        MessageRow stored = row(42);
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.send(senderId, conversationId, request());

        assertThat(result.created()).isTrue();
        assertThat(result.message().id()).isEqualTo(stored.id());
        assertThat(result.message().seq()).isEqualTo(42);
        assertThat(result.message().senderDisplayName()).isEqualTo("Sender Name");
        assertThat(result.message().clientMessageId()).isEqualTo(clientMessageId);

        verify(messageRepository).insertIgnoringDuplicateKey(
                conversationId, senderId, clientMessageId, "TEXT", "hello", null);
        verify(conversationRepository).advanceLastMessage(conversationId, stored.id(), 42L);
        verify(memberRepository).advanceReadWatermark(conversationId, senderId, 42L);

        ArgumentCaptor<MessageCreatedEvent> event = ArgumentCaptor.forClass(MessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().message().seq()).isEqualTo(42);
    }

    @Test
    void send_notMember_throwsNotMember() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(messageRepository, never()).insertIgnoringDuplicateKey(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_missingClientMessageId_throwsValidation() {
        memberOk();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(null, "TEXT", "x", null, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_blankTextContent_throwsValidation() {
        memberOk();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "TEXT", "   ", null, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void send_systemType_rejected() {
        memberOk();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "SYSTEM", "x", null, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void send_unknownType_rejected() {
        memberOk();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "EMOJI", "x", null, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void send_duplicateInSameConversation_returnsExistingAndRebroadcasts() {
        memberOk();
        MessageRow stored = row(41);
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(0); // conflict — no row written
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.send(senderId, conversationId, request());

        assertThat(result.created()).isFalse();
        assertThat(result.message().id()).isEqualTo(stored.id());
        assertThat(result.message().seq()).isEqualTo(stored.seq());
        // No state mutation on the dedup path…
        verify(conversationRepository, never()).advanceLastMessage(any(), any(), anyLong());
        // …but the retry signal is re-broadcast so the sender can reach SENT.
        ArgumentCaptor<MessageCreatedEvent> event = ArgumentCaptor.forClass(MessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().message().id()).isEqualTo(stored.id());
        assertThat(event.getValue().message().seq()).isEqualTo(stored.seq());
    }

    @Test
    void send_duplicateInOtherConversation_throwsValidation() {
        memberOk();
        MessageRow foreign = new MessageRow(
                UUID.randomUUID(), UUID.randomUUID() /* other conv */, 41L, senderId,
                "Sender", Message.Type.TEXT, "hello", clientMessageId, null, Instant.now(), null, null);
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(foreign));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_replyToMissing_throwsMessageNotFound() {
        memberOk();
        UUID missing = UUID.randomUUID();
        when(messageRepository.findById(missing)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "TEXT", "hi", missing, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void send_replyToOtherConversation_throwsValidation() {
        memberOk();
        UUID replyToId = UUID.randomUUID();
        Message foreignMessage = replyMessageIn(UUID.randomUUID());
        when(messageRepository.findById(replyToId)).thenReturn(Optional.of(foreignMessage));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "TEXT", "hi", replyToId, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    private Message replyMessageIn(UUID otherConversationId) {
        Message message = newMessageWithId(UUID.randomUUID());
        com.nexachat.conversation.Conversation conversation =
                com.nexachat.conversation.Conversation.direct(
                        sender(), "a:b");
        ReflectionTestUtils.setField(conversation, "id", otherConversationId);
        ReflectionTestUtils.setField(message, "conversation", conversation);
        return message;
    }

    private User sender() {
        User user = User.create("+84901112233", "hash", "Sender");
        ReflectionTestUtils.setField(user, "id", senderId);
        return user;
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

    @Test
    void send_textWithAttachmentIds_throwsValidation() {
        memberOk();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "TEXT", "hi", null,
                                List.of(UUID.randomUUID()))));

        // Contract failure happens before the insert — nothing to roll back.
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_mediaTypeWithoutAttachmentIds_throwsValidation() {
        memberOk();
        for (String type : new String[] {"IMAGE", "VIDEO", "AUDIO", "FILE"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> messageService.send(senderId, conversationId,
                            new SendMessageRequest(UUID.randomUUID(), type, null, null, List.of())));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }
        verify(messageRepository, never()).insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_moreThanTenAttachments_throwsValidation() {
        memberOk();
        List<UUID> ids = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> UUID.randomUUID()).toList();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "FILE", null, null, ids)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_contentOverMaximumLength_throwsValidationBeforeInsert() {
        memberOk();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "TEXT",
                                "x".repeat(MessageService.MAX_CONTENT_LENGTH + 1), null, List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_bindsOwnedUnboundAttachments() {
        memberOk();
        MessageRow stored = row(42);
        MessageAttachment attachment = uploadAttachment(senderId, "image/png");
        UUID attachmentId = attachment.getId();
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));
        when(messageRepository.getReferenceById(stored.id())).thenReturn(newMessageWithId(stored.id()));
        when(attachmentRepository.findAllById(List.of(attachmentId))).thenReturn(List.of(attachment));

        MessageService.SendResult result = messageService.send(senderId, conversationId,
                new SendMessageRequest(clientMessageId, "IMAGE", null, null, List.of(attachmentId)));

        assertThat(result.created()).isTrue();
        assertThat(attachment.getMessage()).isNotNull();
        assertThat(attachment.getMessage().getId()).isEqualTo(stored.id());
    }

    @Test
    void send_attachmentOwnedByAnotherUser_throwsValidation() {
        memberOk();
        MessageRow stored = row(42);
        MessageAttachment attachment = uploadAttachment(UUID.randomUUID() /* other owner */, "image/png");
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));
        when(attachmentRepository.findAllById(List.of(attachment.getId()))).thenReturn(List.of(attachment));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "IMAGE", null, null,
                                List.of(attachment.getId()))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_attachmentAlreadyBound_throwsValidation() {
        memberOk();
        MessageRow stored = row(42);
        MessageAttachment attachment = uploadAttachment(senderId, "image/png");
        attachment.bindTo(newMessageWithId(UUID.randomUUID())); // bound elsewhere
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));
        when(attachmentRepository.findAllById(List.of(attachment.getId()))).thenReturn(List.of(attachment));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "IMAGE", null, null,
                                List.of(attachment.getId()))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_mimeTypeMismatch_throwsValidation() {
        memberOk();
        MessageRow stored = row(42);
        MessageAttachment pdf = uploadAttachment(senderId, "application/pdf");
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));
        when(attachmentRepository.findAllById(List.of(pdf.getId()))).thenReturn(List.of(pdf));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "IMAGE", null, null, List.of(pdf.getId()))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void send_unknownAttachmentId_throwsFileNotFound() {
        memberOk();
        MessageRow stored = row(42);
        UUID attachmentId = UUID.randomUUID();
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));
        when(attachmentRepository.findAllById(List.of(attachmentId))).thenReturn(List.of());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId,
                        new SendMessageRequest(clientMessageId, "FILE", null, null, List.of(attachmentId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        // The whole send transaction rolls back — no broadcast either.
        verify(eventPublisher, never()).publishEvent(any());
    }

    private MessageAttachment uploadAttachment(UUID ownerId, String mimeType) {
        User owner = User.create("+84901112233", "hash", "Owner");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        MessageAttachment attachment = MessageAttachment.upload(
                owner, "attachments/" + UUID.randomUUID() + ".png", "photo.png",
                mimeType, 128L, null, null, null);
        ReflectionTestUtils.setField(attachment, "id", UUID.randomUUID());
        return attachment;
    }

    // ---------------------------------------------------------------- markRead

    @Test
    void markRead_happy_advancesWatermarkAndPublishes() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);
        when(memberRepository.findReadWatermark(conversationId, senderId)).thenReturn(88L);

        messageService.markRead(senderId, conversationId, new ReadRequest(88L));

        verify(memberRepository).advanceReadWatermark(conversationId, senderId, 88L);
        ArgumentCaptor<MessageReadEvent> event = ArgumentCaptor.forClass(MessageReadEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(senderId);
        assertThat(event.getValue().upToSeq()).isEqualTo(88L);
    }

    @Test
    void markRead_lowerRequestBroadcastsPersistedWatermark() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);
        when(memberRepository.findReadWatermark(conversationId, senderId)).thenReturn(50L);

        messageService.markRead(senderId, conversationId, new ReadRequest(40L));

        verify(memberRepository).advanceReadWatermark(conversationId, senderId, 40L);
        ArgumentCaptor<MessageReadEvent> event = ArgumentCaptor.forClass(MessageReadEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().upToSeq()).isEqualTo(50L);
    }

    @Test
    void markRead_futureSeq_throwsValidationWithoutAdvancing() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.markRead(senderId, conversationId, new ReadRequest(101L)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).advanceReadWatermark(any(), any(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markRead_notMember_throws() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.markRead(senderId, conversationId, new ReadRequest(1L)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(memberRepository, never()).advanceReadWatermark(any(), any(), anyLong());
    }

    @Test
    void markRead_conversationMissing_throws404() {
        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.markRead(senderId, conversationId, new ReadRequest(1L)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void markRead_negativeSeq_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.markRead(senderId, conversationId, new ReadRequest(-5L)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).advanceReadWatermark(any(), any(), anyLong());
    }

    // --------------------------------------------------------------- history

    @Test
    void getHistory_happy_returnsPageWithCursor() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        List<MessageRow> rows = List.of(row(35), row(34), row(33), row(32)); // limit 3 + 1 look-ahead
        when(messageRepository.findPageRows(eq(conversationId), eq(40L), any(Pageable.class)))
                .thenReturn(rows);

        MessagePageResponse page = messageService.getHistory(senderId, conversationId, 40L, null, 3);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0).seq()).isEqualTo(35);
        assertThat(page.nextBeforeSeq()).isEqualTo(33); // oldest in page = next cursor
    }

    @Test
    void getHistory_lastPage_hasMoreFalse() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        when(messageRepository.findPageRows(eq(conversationId), eq(33L), any(Pageable.class)))
                .thenReturn(List.of(row(2), row(1)));

        MessagePageResponse page = messageService.getHistory(senderId, conversationId, 33L, null, 30);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextBeforeSeq()).isEqualTo(1);
    }

    @Test
    void getHistory_emptyPage_nextBeforeSeqNull() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        when(messageRepository.findPageRows(eq(conversationId), any(), any(Pageable.class)))
                .thenReturn(List.of());

        MessagePageResponse page = messageService.getHistory(senderId, conversationId, 1L, null, 30);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextBeforeSeq()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void getHistory_notMember_throws() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.getHistory(senderId, conversationId, null, null, 30));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void getHistory_conversationMissing_throws404() {
        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.getHistory(senderId, conversationId, null, null, 30));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
        verify(memberRepository, never()).existsByConversation_IdAndUser_Id(any(), any());
    }

    @Test
    void getHistory_afterSeq_returnsForwardPageOldestFirst() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        // Forward page: rows arrive ASC; page size +1 look-ahead still detects hasMore.
        when(messageRepository.findRowsAfter(eq(conversationId), eq(100L), any(Pageable.class)))
                .thenReturn(List.of(row(101), row(102), row(103), row(104)));

        MessagePageResponse page =
                messageService.getHistory(senderId, conversationId, null, 100L, 3);

        assertThat(page.items()).extracting(MessageResponse::seq)
                .containsExactly(101L, 102L, 103L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextBeforeSeq()).isNull(); // forward pages carry no backward cursor
        verify(messageRepository, never()).findPageRows(any(), any(), any());
    }

    @Test
    void getHistory_beforeAndAfterSeq_throwsValidation() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.getHistory(senderId, conversationId, 10L, 5L, 30));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}
