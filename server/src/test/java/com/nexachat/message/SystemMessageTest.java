package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.MessageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link MessageService#postSystemMessage} — the
 * server-generated SYSTEM line used by group membership events.
 */
@ExtendWith(MockitoExtension.class)
class SystemMessageTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageAttachmentRepository attachmentRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MessageService messageService;

    private final UUID actorId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, attachmentRepository, conversationRepository,
                memberRepository, storageService, eventPublisher);
        lenient().when(attachmentRepository.findByMessage_IdIn(any())).thenReturn(List.of());
    }

    @Test
    void postSystemMessage_happy_writesSystemRowAndBroadcasts() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, actorId))
                .thenReturn(true);
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        MessageRow stored = new MessageRow(
                UUID.randomUUID(), conversationId, 77L, actorId, "Admin",
                Message.Type.SYSTEM, "Admin added Bob", UUID.randomUUID(),
                null, Instant.now(), null);
        when(messageRepository.findRowBySenderAndClientMessageId(eq(actorId), any()))
                .thenReturn(Optional.of(stored));

        var response = messageService.postSystemMessage(
                actorId, conversationId, "Admin added Bob");

        assertThat(response.type()).isEqualTo("SYSTEM");
        assertThat(response.seq()).isEqualTo(77L);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).insertIgnoringDuplicateKey(
                eq(conversationId), eq(actorId), any(UUID.class),
                type.capture(), eq("Admin added Bob"), isNull());
        assertThat(type.getValue()).isEqualTo("SYSTEM");

        verify(conversationRepository).advanceLastMessage(conversationId, stored.id(), 77L);
        verify(memberRepository).advanceReadWatermark(conversationId, actorId, 77L);

        ArgumentCaptor<MessageCreatedEvent> event = ArgumentCaptor.forClass(MessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().message().type()).isEqualTo("SYSTEM");
    }

    @Test
    void postSystemMessage_blankContent_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.postSystemMessage(actorId, conversationId, "  "));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void postSystemMessage_actorNotMember_throwsNotMember() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, actorId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.postSystemMessage(actorId, conversationId, "x"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(messageRepository, never()).insertIgnoringDuplicateKey(
                any(), any(), any(), any(), any(), any());
    }
}
