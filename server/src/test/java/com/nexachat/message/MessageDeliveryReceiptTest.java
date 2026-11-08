package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.ChatAckRequest;
import com.nexachat.message.dto.DeliveryReceiptPayload;
import com.nexachat.message.dto.DeliveryRequest;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.StompUserPrincipal;
import com.nexachat.ws.WsEventType;
import com.nexachat.ws.WsMessageController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageDeliveryReceiptTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SimpMessagingTemplate messaging;

    private MessageService messageService;
    private MessageController messageController;
    private WsMessageController wsController;
    private MessageBroadcastListener broadcastListener;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final JwtPrincipal jwtPrincipal = new JwtPrincipal(userId);
    private final StompUserPrincipal stompPrincipal = new StompUserPrincipal(userId);

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository,
                mock(MessageAttachmentRepository.class),
                mock(MessageReactionRepository.class),
                conversationRepository,
                memberRepository,
                mock(PinnedMessageRepository.class),
                mock(com.nexachat.attachment.StorageService.class),
                eventPublisher
        );
        messageController = new MessageController(messageService);
        wsController = new WsMessageController(messageService, messaging);
        broadcastListener = new MessageBroadcastListener(messaging, memberRepository);
    }

    @Test
    void markDeliveredAdvancesWatermarkAndPublishesEvent() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);
        when(memberRepository.findDeliveredWatermark(conversationId, userId)).thenReturn(42L);

        messageService.markDelivered(conversationId, userId, 42L);

        verify(memberRepository).advanceDeliveredWatermark(conversationId, userId, 42L);
        ArgumentCaptor<MessageDeliveredEvent> captor = ArgumentCaptor.forClass(MessageDeliveredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        MessageDeliveredEvent published = captor.getValue();
        assertThat(published.conversationId()).isEqualTo(conversationId);
        assertThat(published.userId()).isEqualTo(userId);
        assertThat(published.upToSeq()).isEqualTo(42L);
    }

    @Test
    void markDeliveredRejectsNegativeSeq() {
        assertThatThrownBy(() -> messageService.markDelivered(conversationId, userId, -1L))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void markDeliveredRejectsSeqExceedingMaxSeq() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

        assertThatThrownBy(() -> messageService.markDelivered(conversationId, userId, 99L))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void markDeliveredRejectsNonMember() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);

        assertThatThrownBy(() -> messageService.markDelivered(conversationId, userId, 10L))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void broadcastListenerEmitsDeliveredEventOnTopic() {
        MessageDeliveredEvent event = new MessageDeliveredEvent(conversationId, userId, 42L);
        broadcastListener.onMessageDelivered(event);

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(eq("/topic/conversations." + conversationId), captor.capture());

        EventEnvelope envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(WsEventType.MESSAGE_DELIVERED);
        assertThat(envelope.conversationId()).isEqualTo(conversationId);
        assertThat(envelope.payload()).isInstanceOf(DeliveryReceiptPayload.class);
        DeliveryReceiptPayload payload = (DeliveryReceiptPayload) envelope.payload();
        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.upToSeq()).isEqualTo(42L);
    }

    @Test
    void restEndpointMarkDeliveredReturnsNoContent() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

        ResponseEntity<Void> response = messageController.markDelivered(
                jwtPrincipal, conversationId, new DeliveryRequest(25L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(memberRepository).advanceDeliveredWatermark(conversationId, userId, 25L);
    }

    @Test
    void wsControllerHandlesScopedDeliveredMapping() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

        wsController.delivered(conversationId, new DeliveryRequest(30L), stompPrincipal);

        verify(memberRepository).advanceDeliveredWatermark(conversationId, userId, 30L);
    }

    @Test
    void wsControllerHandlesGlobalChatDeliveryAck() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

        wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 35L), stompPrincipal);

        verify(memberRepository).advanceDeliveredWatermark(conversationId, userId, 35L);
    }

    @Test
    void wsControllerHandlesGlobalChatReadAck() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

        wsController.chatReadAck(new ChatAckRequest(conversationId, 40L), stompPrincipal);

        verify(memberRepository).advanceReadWatermark(conversationId, userId, 40L);
    }
}
