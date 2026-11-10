package com.nexachat.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.dto.MemberFlagsRow;
import com.nexachat.message.*;
import com.nexachat.message.dto.*;
import com.nexachat.notification.*;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.ws.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Milestone M1 Adversarial Stress & Vulnerability Probes")
public class BackendM1AdversarialChallengeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // PROBE 1: STOMP Delivery and Read Ack Monotonicity & Boundary Enforcement
    // =========================================================================
    @Nested
    @DisplayName("Probe 1: STOMP Delivery & Read Acks")
    class StompDeliveryAndReadAckProbes {

        @Mock private ConversationRepository conversationRepository;
        @Mock private ConversationMemberRepository memberRepository;
        @Mock private MessageRepository messageRepository;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private SimpMessagingTemplate messaging;

        private MessageService messageService;
        private WsMessageController wsController;

        private final UUID userId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();
        private final StompUserPrincipal principal = new StompUserPrincipal(userId);

        @BeforeEach
        void setUp() {
            messageService = new MessageService(
                    messageRepository,
                    mock(MessageAttachmentRepository.class),
                    mock(MessageReactionRepository.class),
                    mock(ReactionEventRepository.class),
                    conversationRepository,
                    memberRepository,
                    mock(PinnedMessageRepository.class),
                    mock(com.nexachat.attachment.StorageService.class),
                    eventPublisher,
                    new SyncProperties()
            );
            wsController = new WsMessageController(messageService, messaging);
        }

        @Test
        @DisplayName("1.1 Out-of-order delivery acks do not regress monotonic watermark")
        void testDeliveryAckOutOfOrderMonotonicity() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

            AtomicLong persistedWatermark = new AtomicLong(0L);
            doAnswer(inv -> {
                long upToSeq = inv.getArgument(2);
                persistedWatermark.accumulateAndGet(upToSeq, Math::max);
                return 1;
            }).when(memberRepository).advanceDeliveredWatermark(eq(conversationId), eq(userId), anyLong());

            when(memberRepository.findDeliveredWatermark(conversationId, userId))
                    .thenAnswer(inv -> persistedWatermark.get());

            // Step 1: Client receives ack for seq 25
            wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 25L), principal);
            assertThat(persistedWatermark.get()).isEqualTo(25L);

            // Step 2: Out-of-order delayed ack for seq 10 arrives
            wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 10L), principal);
            // Monotonic property: watermark must remain 25, not regress to 10
            assertThat(persistedWatermark.get()).isEqualTo(25L);

            ArgumentCaptor<MessageDeliveredEvent> captor = ArgumentCaptor.forClass(MessageDeliveredEvent.class);
            verify(eventPublisher, times(2)).publishEvent(captor.capture());
            List<MessageDeliveredEvent> publishedEvents = captor.getAllValues();
            assertThat(publishedEvents.get(0).upToSeq()).isEqualTo(25L);
            assertThat(publishedEvents.get(1).upToSeq()).isEqualTo(25L); // Second event also preserves 25
        }

        @Test
        @DisplayName("1.2 Duplicate delivery acks are idempotent and safe")
        void testDuplicateDeliveryAcksAreIdempotent() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(100L);
            when(memberRepository.findDeliveredWatermark(conversationId, userId)).thenReturn(42L);

            // Send duplicate ack 42 twice
            wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 42L), principal);
            wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 42L), principal);

            verify(memberRepository, times(2)).advanceDeliveredWatermark(conversationId, userId, 42L);
            verify(eventPublisher, times(2)).publishEvent(any(MessageDeliveredEvent.class));
        }

        @Test
        @DisplayName("1.3 Delivery ack from non-member is rejected with NOT_MEMBER error")
        void testDeliveryAckFromNonMemberIsRejected() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);

            assertThatThrownBy(() -> wsController.chatDeliveryAck(
                    new DeliveryRequest(conversationId, 10L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.NOT_MEMBER);

            verify(memberRepository, never()).advanceDeliveredWatermark(any(), any(), anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("1.4 Delivery ack exceeding conversation maxSeq is rejected")
        void testDeliveryAckExceedingMaxSeqRejected() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(30L);

            assertThatThrownBy(() -> wsController.chatDeliveryAck(
                    new DeliveryRequest(conversationId, 31L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(() -> wsController.chatDeliveryAck(
                    new DeliveryRequest(conversationId, 99999L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            verify(memberRepository, never()).advanceDeliveredWatermark(any(), any(), anyLong());
        }

        @Test
        @DisplayName("1.5 Empty conversation boundary: seq 0 permitted, seq 1 rejected")
        void testEmptyConversationDeliveryAckBoundary() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(0L); // Empty conversation
            when(memberRepository.findDeliveredWatermark(conversationId, userId)).thenReturn(0L);

            // Ack seq 0 succeeds
            wsController.chatDeliveryAck(new DeliveryRequest(conversationId, 0L), principal);
            verify(memberRepository).advanceDeliveredWatermark(conversationId, userId, 0L);

            // Ack seq 1 fails
            assertThatThrownBy(() -> wsController.chatDeliveryAck(
                    new DeliveryRequest(conversationId, 1L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("1.6 Read ack out-of-order and exceeding maxSeq")
        void testReadAckMonotonicityAndValidation() {
            when(conversationRepository.existsById(conversationId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(messageRepository.findMaxSeqByConversationId(conversationId)).thenReturn(50L);

            // Ack seq > 50 rejected
            assertThatThrownBy(() -> wsController.chatReadAck(
                    new ChatAckRequest(conversationId, 51L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            // Ack negative seq rejected
            assertThatThrownBy(() -> wsController.chatReadAck(
                    new ChatAckRequest(conversationId, -5L), principal))
                    .isInstanceOf(SendRejectedException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            // Valid read ack
            when(memberRepository.findReadWatermark(conversationId, userId)).thenReturn(20L);
            wsController.chatReadAck(new ChatAckRequest(conversationId, 20L), principal);
            verify(memberRepository).advanceReadWatermark(conversationId, userId, 20L);
            verify(eventPublisher).publishEvent(any(MessageReadEvent.class));
        }

        @Test
        @DisplayName("1.7 Missing conversationId in ack payload throws VALIDATION_FAILED")
        void testMissingConversationIdInPayload() {
            assertThatThrownBy(() -> wsController.chatDeliveryAck(new DeliveryRequest(null, 10L), principal))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(() -> wsController.chatReadAck(new ChatAckRequest(null, 10L), principal))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    // =========================================================================
    // PROBE 2: Delta Sync Endpoint POST /api/v1/sync Adversarial Cases
    // =========================================================================
    @Nested
    @DisplayName("Probe 2: Delta Sync Endpoint")
    class DeltaSyncEndpointProbes {

        @Mock private ConversationRepository conversationRepository;
        @Mock private ConversationMemberRepository memberRepository;
        @Mock private MessageRepository messageRepository;
        @Mock private MessageReactionRepository reactionRepository;

        private MessageService messageService;
        private SyncController syncController;

        private final UUID userId = UUID.randomUUID();
        private final JwtPrincipal principal = new JwtPrincipal(userId);

        @BeforeEach
        void setUp() {
            messageService = new MessageService(
                    messageRepository,
                    mock(MessageAttachmentRepository.class),
                    reactionRepository,
                    mock(ReactionEventRepository.class),
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
        @DisplayName("2.1 Empty watermarks list or null request rejected with VALIDATION_FAILED")
        void testEmptyWatermarksRejected() {
            assertThatThrownBy(() -> syncController.syncDelta(principal, null))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            SyncRequest emptyReq = new SyncRequest(List.of(), 50, null);
            assertThatThrownBy(() -> syncController.syncDelta(principal, emptyReq))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

            SyncRequest nullListReq = new SyncRequest(null, 50, null);
            assertThatThrownBy(() -> syncController.syncDelta(principal, nullListReq))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("2.2 Large watermark list (>100 conversations) clamped safely without error")
        void testLargeWatermarkListHandledSafely() {
            int count = 200;
            List<SyncRequest.ConversationWatermark> watermarks = new ArrayList<>(count);
            lenient().when(memberRepository.findMemberFlags(eq(userId), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Collection<UUID> ids = invocation.getArgument(1);
                return ids.stream()
                        .map(id -> new MemberFlagsRow(id, false, null, false, false, null))
                        .toList();
            });
            for (int i = 0; i < count; i++) {
                UUID cId = UUID.randomUUID();
                watermarks.add(new SyncRequest.ConversationWatermark(cId, (long) i));
                lenient().when(messageRepository.findRowsAfter(eq(cId), eq((long) i), any(Pageable.class)))
                        .thenReturn(List.of());
                lenient().when(messageRepository.findMaxSeqByConversationId(cId)).thenReturn((long) i);
            }

            SyncRequest request = new SyncRequest(watermarks, 50, null);
            var response = syncController.syncDelta(principal, request);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().deltas()).hasSize(100);
        }

        @Test
        @DisplayName("2.3 Watermark for non-member conversation is silently omitted without leaking")
        void testNonMemberConversationOmitted() {
            UUID memberConv = UUID.randomUUID();
            UUID nonMemberConv = UUID.randomUUID();

            when(memberRepository.findMemberFlags(eq(userId), any())).thenReturn(
                    List.of(new MemberFlagsRow(memberConv, false, null, false, false, null)));
            when(messageRepository.findRowsAfter(eq(memberConv), anyLong(), any(Pageable.class))).thenReturn(List.of());
            when(messageRepository.findMaxSeqByConversationId(memberConv)).thenReturn(10L);

            SyncRequest request = new SyncRequest(
                    List.of(
                            new SyncRequest.ConversationWatermark(memberConv, 5L),
                            new SyncRequest.ConversationWatermark(nonMemberConv, 5L)
                    ),
                    50,
                    null
            );

            var response = syncController.syncDelta(principal, request);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().deltas()).hasSize(1);
            assertThat(response.getBody().deltas().get(0).conversationId()).isEqualTo(memberConv);
        }

        @Test
        @DisplayName("2.4 Deleted messages have content masked to null in newMessages")
        void testDeletedMessageContentMasked() {
            UUID convId = UUID.randomUUID();
            when(memberRepository.findMemberFlags(eq(userId), any())).thenReturn(
                    List.of(new MemberFlagsRow(convId, false, null, false, false, null)));

            Instant now = Instant.now();
            MessageRow deletedRow = new MessageRow(
                    UUID.randomUUID(), convId, 101L, userId, "User",
                    Message.Type.TEXT, "Secret content that was deleted", UUID.randomUUID(), null,
                    now.minusSeconds(60), null, now
            );

            when(messageRepository.findRowsAfter(eq(convId), eq(100L), any(Pageable.class)))
                    .thenReturn(List.of(deletedRow));
            when(messageRepository.findMaxSeqByConversationId(convId)).thenReturn(101L);

            SyncRequest request = new SyncRequest(
                    List.of(new SyncRequest.ConversationWatermark(convId, 100L)),
                    50,
                    null
            );

            var response = syncController.syncDelta(principal, request);
            assertThat(response.getBody()).isNotNull();
            var deltas = response.getBody().deltas();
            assertThat(deltas.get(0).newMessages()).hasSize(1);
            MessageResponse returnedMsg = deltas.get(0).newMessages().get(0);
            assertThat(returnedMsg.deletedAt()).isEqualTo(now);
            assertThat(returnedMsg.content()).as("Deleted message content must be masked to null").isNull();
            assertThat(returnedMsg.attachments()).isEmpty();
            assertThat(returnedMsg.reactions()).isEmpty();
        }

        @Test
        @DisplayName("2.5 LimitPerConv clamping boundaries: 5000 -> 100, -10 -> 1")
        void testLimitPerConvClamping() {
            UUID convId = UUID.randomUUID();
            when(memberRepository.findMemberFlags(eq(userId), any())).thenReturn(
                    List.of(new MemberFlagsRow(convId, false, null, false, false, null)));
            when(messageRepository.findMaxSeqByConversationId(convId)).thenReturn(0L);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(messageRepository.findRowsAfter(eq(convId), eq(0L), pageableCaptor.capture()))
                    .thenReturn(List.of());

            // Limit 5000 should be clamped to 100 (+1 for hasMore = 101)
            syncController.syncDelta(principal, new SyncRequest(
                    List.of(new SyncRequest.ConversationWatermark(convId, 0L)),
                    5000,
                    null
            ));
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(101);

            // Limit -10 should be clamped to 1 (+1 for hasMore = 2)
            syncController.syncDelta(principal, new SyncRequest(
                    List.of(new SyncRequest.ConversationWatermark(convId, 0L)),
                    -10,
                    null
            ));
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        }
    }

    // =========================================================================
    // PROBE 3: FCM Push Pointer Size & Gateway Reliability
    // =========================================================================
    @Nested
    @DisplayName("Probe 3: FCM Push Pointer & Gateway")
    class FcmPushPointerProbes {

        @Mock private UserDeviceTokenRepository tokenRepository;

        private MockFcmGateway mockGateway;
        private DefaultFcmPushService pushService;

        private final UUID userId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            mockGateway = new MockFcmGateway();
            pushService = new DefaultFcmPushService(tokenRepository, mockGateway);
        }

        @Test
        @DisplayName("3.1 Stress-test payload size with max Vietnamese Unicode strings (<500B)")
        void testPayloadSizeConstraintUnderExtremeUnicode() throws Exception {
            com.nexachat.user.User user = mock(com.nexachat.user.User.class);
            UserDeviceToken token = UserDeviceToken.create(user, "test-fcm-token", UserDeviceToken.Platform.ANDROID, "Pixel");
            when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

            // Max display name (60 chars of complex Vietnamese Unicode)
            String extremeSenderName = "Nguyễn Thị Thuỳ Dương Nguyễn Thị Thuỳ Dương Nguyễn Thị Thuỳ "; // 60 chars

            pushService.sendNewMessageNotification(
                    userId, conversationId, UUID.randomUUID(), 999999L,
                    extremeSenderName
            );

            List<MockFcmGateway.SentPush> sent = mockGateway.getSentPushes();
            assertThat(sent).hasSize(1);
            Map<String, String> data = sent.get(0).data();

            byte[] jsonBytes = objectMapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
            assertThat(jsonBytes.length)
                    .as("Even with maximum Vietnamese Unicode strings, FCM pointer must be < 500 bytes")
                    .isLessThan(500);

            // Also test CALL_INVITE with maximum Unicode caller name
            mockGateway.clear();
            pushService.sendCallInviteNotification(
                    userId, UUID.randomUUID(), conversationId, UUID.randomUUID(),
                    extremeSenderName, "VIDEO", System.currentTimeMillis()
            );

            sent = mockGateway.getSentPushes();
            assertThat(sent).hasSize(1);
            byte[] callJsonBytes = objectMapper.writeValueAsString(sent.get(0).data()).getBytes(StandardCharsets.UTF_8);
            assertThat(callJsonBytes.length)
                    .as("Call invite payload with extreme caller name must be < 500 bytes")
                    .isLessThan(500);
        }

        @Test
        @DisplayName("3.2 New-message payload is a pure pointer")
        void testNewMessagePayloadIsPurePointer() {
            com.nexachat.user.User user = mock(com.nexachat.user.User.class);
            UserDeviceToken token = UserDeviceToken.create(user, "test-fcm-token", UserDeviceToken.Platform.ANDROID, "Pixel");
            when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

            pushService.sendNewMessageNotification(
                    userId, conversationId, UUID.randomUUID(), 1L, null
            );

            Map<String, String> data = mockGateway.getSentPushes().get(0).data();
            assertThat(data).containsOnlyKeys("type", "conversationId", "messageId", "seq", "senderName");
            assertThat(data.get("senderName")).isEqualTo("");
        }

        @Test
        @DisplayName("3.3 Unregistered token results in token deletion from DB")
        void testUnregisteredTokenDeletion() {
            mockGateway.setSimulateUnregistered(true);
            com.nexachat.user.User user = mock(com.nexachat.user.User.class);
            UserDeviceToken token = UserDeviceToken.create(user, "dead-token", UserDeviceToken.Platform.ANDROID, "Old Phone");
            when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

            pushService.sendNewMessageNotification(
                    userId, conversationId, UUID.randomUUID(), 1L, "Alice"
            );

            verify(tokenRepository).deleteByToken("dead-token");
        }

        @Test
        @DisplayName("3.4 MockFcmGateway concurrent access safety")
        void testMockGatewayConcurrency() throws Exception {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        mockGateway.sendDataPush("token-" + idx, Map.of("key", "val-" + idx), "HIGH", 60L);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(completed).isTrue();
            assertThat(mockGateway.getSentPushes()).hasSize(threadCount);
        }
    }

    // =========================================================================
    // PROBE 4: WebRTC 15-Second Grace Buffer & Reconnection Protection
    // =========================================================================
    @Nested
    @DisplayName("Probe 4: WebRTC 15s Grace Buffer")
    class WebRtcGraceBufferProbes {

        @Mock private CallRegistry callRegistry;
        @Mock private CallService callService;
        @Mock private PresenceSessionState presenceState;

        private CallTimeoutScheduler scheduler;
        private CallDisconnectListener disconnectListener;
        private final RecordingScheduledExecutor executor = new RecordingScheduledExecutor();

        private final UUID userId = UUID.randomUUID();
        private final UUID peerId = UUID.randomUUID();
        private final UUID callId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            scheduler = new CallTimeoutScheduler(callService, callRegistry, 45_000L, executor);
            disconnectListener = new CallDisconnectListener(presenceState, scheduler, callService);
        }

        @Test
        @DisplayName("4.1 ACTIVE call disconnect wait is scheduled for exactly 15,000ms")
        void testActiveCallDisconnectSchedules15sDelay() {
            Instant now = Instant.now();
            CallSession activeSession = new CallSession(
                    callId, conversationId, userId, peerId,
                    CallState.ACTIVE, null, now, now.plusSeconds(5), null, null);

            when(callRegistry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

            disconnectListener.onSessionDisconnected(createDisconnectEvent(userId));

            assertThat(executor.tasks).hasSize(1);
            assertThat(executor.delays.get(0))
                    .as("Active call disconnect must schedule exactly 15,000ms grace window")
                    .isEqualTo(15_000L);
        }

        @Test
        @DisplayName("4.2 Non-active call disconnect schedules 50ms quick cleanup")
        void testNonActiveCallDisconnectUses50msDelay() {
            when(callRegistry.activeCallForUser(userId)).thenReturn(Optional.empty());

            disconnectListener.onSessionDisconnected(createDisconnectEvent(userId));

            assertThat(executor.tasks).hasSize(1);
            assertThat(executor.delays.get(0)).isEqualTo(50L);
        }

        @Test
        @DisplayName("4.3 Reconnection within 15s grace cancels call termination")
        void testReconnectWithinGraceCancelsDisconnect() {
            Instant now = Instant.now();
            CallSession activeSession = new CallSession(
                    callId, conversationId, userId, peerId,
                    CallState.ACTIVE, null, now, now.plusSeconds(5), null, null);

            when(callRegistry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

            // User disconnects
            disconnectListener.onSessionDisconnected(createDisconnectEvent(userId));
            assertThat(executor.tasks).hasSize(1);
            Runnable cleanupTask = executor.tasks.get(0);

            // Reconnection event happens: presence becomes online again before 15s fires
            when(presenceState.isOnline(userId)).thenReturn(true);

            // 15 seconds elapse and scheduler fires the task
            cleanupTask.run();

            // Verification: callService.disconnect MUST NOT be called! Call survives!
            verify(callService, never()).disconnect(userId);
        }

        @Test
        @DisplayName("4.4 Expired 15s grace without reconnect terminates call")
        void testGraceExpiryWithoutReconnectTerminatesCall() {
            Instant now = Instant.now();
            CallSession activeSession = new CallSession(
                    callId, conversationId, userId, peerId,
                    CallState.ACTIVE, null, now, now.plusSeconds(5), null, null);

            when(callRegistry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

            // User disconnects
            disconnectListener.onSessionDisconnected(createDisconnectEvent(userId));
            assertThat(executor.tasks).hasSize(1);
            Runnable cleanupTask = executor.tasks.get(0);

            // 15s elapse and user is STILL offline
            when(presenceState.isOnline(userId)).thenReturn(false);

            // Cleanup task fires
            cleanupTask.run();

            // Verification: callService.disconnect MUST be called after grace expiration
            verify(callService, times(1)).disconnect(userId);
        }

        private SessionDisconnectEvent createDisconnectEvent(UUID uid) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            accessor.setUser(new StompUserPrincipal(uid));
            var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            return new SessionDisconnectEvent(this, message, "session-" + uid, CloseStatus.NORMAL);
        }

        private static class RecordingScheduledExecutor extends AbstractExecutorService implements ScheduledExecutorService {
            final List<Runnable> tasks = new ArrayList<>();
            final List<Long> delays = new ArrayList<>();

            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                tasks.add(command);
                delays.add(unit.toMillis(delay));
                return mock(ScheduledFuture.class);
            }

            @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
            @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { throw new UnsupportedOperationException(); }
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { tasks.add(command); delays.add(0L); }
        }
    }
}
