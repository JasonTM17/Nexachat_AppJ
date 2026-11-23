package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.e2ee.E2eeDeviceService;
import com.nexachat.attachment.StorageService;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.SyncProperties;
import com.nexachat.user.UserBlockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V38 send-time block policy on DIRECT conversations:
 *
 * <ul>
 *   <li>caller blocked the peer → typed {@code BLOCKED} refusal (the caller
 *       created the edge — telling them leaks nothing);</li>
 *   <li>peer blocked the caller → accepted and stored
 *       {@code peer_suppressed}: the send looks successful to the blocked
 *       user, never advances the shared inbox pointer, and only their own
 *       read models ever show it.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DirectBlockSendTest {

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
    private PinnedMessageRepository pinnedMessageRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private UserBlockRepository blockRepository;

    private MessageService messageService;

    private final UUID senderId = UUID.randomUUID();
    private final UUID peerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID clientMessageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, null, attachmentRepository, reactionRepository,
                mock(ReactionEventRepository.class), conversationRepository, memberRepository,
                pinnedMessageRepository, storageService, eventPublisher, new SyncProperties(),
                Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC),
                new MessageRecallProperties(), null,
                org.mockito.Mockito.mock(
                        org.springframework.beans.factory.ObjectProvider.class),
                blockRepository);
        lenient().when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, senderId))
                .thenReturn(true);
        lenient().when(conversationRepository.findByIdForUpdate(conversationId))
                .thenAnswer(i -> Optional.of(directConversation()));
        lenient().when(memberRepository.findOtherMemberIds(conversationId, senderId))
                .thenReturn(List.of(peerId));
        lenient().when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.empty());
        lenient().when(attachmentRepository.findByMessage_IdIn(any())).thenReturn(List.of());
        lenient().when(reactionRepository.findRowsByMessageIdIn(any())).thenReturn(List.of());
        lenient().when(messageRepository.findAdmittedReplyRefsByIdIn(any(), any()))
                .thenReturn(List.of());
        lenient().when(conversationRepository.incrementAndGetChangeVersion(any())).thenReturn(1L);
        lenient().when(messageRepository.findE2eeByIdIn(any())).thenReturn(List.of());
    }

    private Conversation directConversation() {
        Conversation conversation = mock(Conversation.class);
        lenient().when(conversation.getType()).thenReturn(Conversation.Type.DIRECT);
        lenient().when(conversation.getId()).thenReturn(conversationId);
        return conversation;
    }

    private SendMessageRequest request() {
        return new SendMessageRequest(clientMessageId, "TEXT", "hello", null, List.of());
    }

    @Test
    void send_callerBlockedPeer_throwsBlockedAndInsertsNothing() {
        when(blockRepository.existsByBlocker_IdAndBlocked_Id(senderId, peerId)).thenReturn(true);
        when(blockRepository.existsByBlocker_IdAndBlocked_Id(peerId, senderId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> messageService.send(senderId, conversationId, request()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.BLOCKED);
        verify(messageRepository, never()).insertIgnoringDuplicateKey(
                any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(conversationRepository, never()).advanceLastMessage(any(), any(), anyLong());
    }

    @Test
    void send_peerBlockedCaller_storesSuppressedAndSkipsInboxPointer() {
        when(blockRepository.existsByBlocker_IdAndBlocked_Id(senderId, peerId)).thenReturn(false);
        when(blockRepository.existsByBlocker_IdAndBlocked_Id(peerId, senderId)).thenReturn(true);
        when(messageRepository.insertIgnoringDuplicateKey(
                eq(conversationId), eq(senderId), eq(clientMessageId),
                eq("TEXT"), eq("hello"), eq(null), eq(true)))
                .thenReturn(1);
        MessageRow stored = new MessageRow(
                UUID.randomUUID(), conversationId, 42L, senderId, "Sender",
                Message.Type.TEXT, "hello", clientMessageId, null, Instant.now(), null, null,
                false, 1L, 1L);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.send(senderId, conversationId, request());

        // The blocked sender sees an ordinary successful send — no signal.
        assertThat(result.created()).isTrue();
        assertThat(result.message().id()).isEqualTo(stored.id());
        verify(messageRepository).insertIgnoringDuplicateKey(
                conversationId, senderId, clientMessageId, "TEXT", "hello", null, true);
        // The shared inbox pointer never points at a suppressed row — that
        // would leak the blocked sender's content into the blocker's preview.
        verify(conversationRepository, never()).advanceLastMessage(any(), any(), anyLong());
        // The sender's own unread stays 0; their preview may lag one message.
        verify(memberRepository).advanceReadWatermark(conversationId, senderId, 42L);
        verify(eventPublisher).publishEvent(any(MessageCreatedEvent.class));
    }

    @Test
    void send_noBlockEdge_insertsUnsuppressedAsBefore() {
        when(blockRepository.existsByBlocker_IdAndBlocked_Id(any(), any())).thenReturn(false);
        when(messageRepository.insertIgnoringDuplicateKey(any(), any(), any(), any(), any(), any(),
                eq(false)))
                .thenReturn(1);
        MessageRow stored = new MessageRow(
                UUID.randomUUID(), conversationId, 42L, senderId, "Sender",
                Message.Type.TEXT, "hello", clientMessageId, null, Instant.now(), null, null,
                false, 1L, 1L);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.send(senderId, conversationId, request());

        assertThat(result.created()).isTrue();
        verify(messageRepository).insertIgnoringDuplicateKey(
                conversationId, senderId, clientMessageId, "TEXT", "hello", null, false);
        verify(conversationRepository).advanceLastMessage(conversationId, stored.id(), 42L);
    }

    @Test
    void send_retryOfPreBlockMessage_stillReplays() {
        // A blocked sender retrying an old clientMessageId replays the stored
        // row rather than evaluating the block again — the idempotency
        // contract outranks the block check.
        MessageRow stored = new MessageRow(
                UUID.randomUUID(), conversationId, 42L, senderId, "Sender",
                Message.Type.TEXT, "hello", clientMessageId, null, Instant.now(), null, null,
                false, 1L, 1L);
        when(messageRepository.findRowBySenderAndClientMessageId(senderId, clientMessageId))
                .thenReturn(Optional.of(stored));

        MessageService.SendResult result = messageService.send(senderId, conversationId, request());

        assertThat(result.created()).isFalse();
        verify(messageRepository, never()).insertIgnoringDuplicateKey(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
