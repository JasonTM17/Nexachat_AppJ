package com.nexachat.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.dto.MemberFlagsRow;
import com.nexachat.message.*;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import com.nexachat.notification.*;
import com.nexachat.notification.dto.DeviceTokenRequest;
import com.nexachat.notification.dto.DeviceTokenResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.StompAuthChannelInterceptor;
import com.nexachat.ws.StompUserPrincipal;
import com.nexachat.ws.dto.WsErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Milestone M1 Iteration 2 Remediation Regression Tests")
public class BackendM1Iteration2RegressionTest {

    // =========================================================================
    // FIX 1: Async Token Deletion Transaction & Unregister Invocation
    // =========================================================================
    @Nested
    @DisplayName("Fix 1: Transactional Async FCM Token Deletion")
    class AsyncFcmTransactionFixTests {

        @Mock private UserDeviceTokenRepository tokenRepository;
        @Mock private UserRepository userRepository;
        @Mock private DeviceTokenService deviceTokenService;

        private MockFcmGateway mockGateway;
        private DefaultFcmPushService pushService;

        private final UUID userId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            mockGateway = new MockFcmGateway();
            pushService = new DefaultFcmPushService(tokenRepository, mockGateway, deviceTokenService);
        }

        @Test
        @DisplayName("1.1 When FCM gateway reports token unregistered, deviceTokenService.unregisterToken is invoked")
        void fcmUnregisteredInvokesDeviceTokenService() {
            mockGateway.setSimulateUnregistered(true);

            User user = mock(User.class);
            UserDeviceToken token = UserDeviceToken.create(user, "unregistered-fcm-token", UserDeviceToken.Platform.ANDROID, "Phone");
            when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

            pushService.sendNewMessageNotification(
                    userId, conversationId, UUID.randomUUID(), 10L, "Sender"
            );

            verify(deviceTokenService).unregisterToken("unregistered-fcm-token");
        }

        @Test
        @DisplayName("1.2 UserDeviceTokenRepository methods and DeviceTokenService.unregisterToken have @Transactional annotation")
        void repositoryAndServiceHaveTransactionalAnnotations() throws NoSuchMethodException {
            Method repoDeleteByToken = UserDeviceTokenRepository.class.getMethod("deleteByToken", String.class);
            assertThat(repoDeleteByToken.isAnnotationPresent(Transactional.class))
                    .as("UserDeviceTokenRepository.deleteByToken must be @Transactional")
                    .isTrue();

            Method repoDeleteByUserAndToken = UserDeviceTokenRepository.class.getMethod("deleteByUserIdAndToken", UUID.class, String.class);
            assertThat(repoDeleteByUserAndToken.isAnnotationPresent(Transactional.class))
                    .as("UserDeviceTokenRepository.deleteByUserIdAndToken must be @Transactional")
                    .isTrue();

            Method serviceUnregister = DeviceTokenService.class.getMethod("unregisterToken", String.class);
            assertThat(serviceUnregister.isAnnotationPresent(Transactional.class))
                    .as("DeviceTokenService.unregisterToken(String) must be @Transactional")
                    .isTrue();
        }

        @Test
        @DisplayName("1.3 DeviceTokenService unregisterToken delegates to repository deleteByToken")
        void deviceTokenServiceUnregisterDelegatesToRepository() {
            DeviceTokenService service = new DeviceTokenService(tokenRepository, userRepository);
            service.unregisterToken("token-to-delete");

            verify(tokenRepository).deleteByToken("token-to-delete");
        }
    }

    // =========================================================================
    // FIX 2: WebRTC Grace Timer Collision Reset & Cancellation
    // =========================================================================
    @Nested
    @DisplayName("Fix 2: WebRTC Disconnect Grace Window Collision Reset")
    class WebRtcGraceTimerCollisionFixTests {

        private final Instant NOW = Instant.parse("2026-11-08T04:00:00Z");

        @Test
        @DisplayName("2.1 Subsequent disconnect check cancels prior pending future and resets full grace window")
        void subsequentDisconnectCancelsPriorTimerAndResetsGrace() {
            UUID userId = UUID.randomUUID();
            UUID peerId = UUID.randomUUID();
            UUID callId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();

            CallSession activeSession = new CallSession(
                    callId, conversationId, userId, peerId,
                    CallState.ACTIVE, null, NOW, NOW.plusSeconds(2), null, null);

            CallRegistry registry = mock(CallRegistry.class);
            when(registry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

            CallService service = mock(CallService.class);
            TestScheduledExecutor executor = new TestScheduledExecutor();
            CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, 15_000, executor);

            // First drop (e.g. Wi-Fi drops at T=0s)
            scheduler.checkDisconnectedUser(userId, () -> {});
            assertThat(executor.futures).hasSize(1);
            TestScheduledFuture<?> firstFuture = executor.futures.get(0);
            assertThat(firstFuture.isCancelled()).isFalse();
            assertThat(executor.delays.get(0)).isEqualTo(15_000L);

            // Second drop (e.g. drops in elevator at T=12s before first timer fires)
            scheduler.checkDisconnectedUser(userId, () -> {});
            assertThat(executor.futures).hasSize(2);
            TestScheduledFuture<?> secondFuture = executor.futures.get(1);

            // Prior timer MUST be cancelled to prevent premature termination
            assertThat(firstFuture.isCancelled()).isTrue();
            assertThat(secondFuture.isCancelled()).isFalse();
            assertThat(executor.delays.get(1)).isEqualTo(15_000L);
        }

        @Test
        @DisplayName("2.2 Reconnecting cancels pending disconnect grace timer")
        void reconnectCancelsPendingDisconnectTimer() {
            UUID userId = UUID.randomUUID();
            UUID peerId = UUID.randomUUID();
            UUID callId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();

            CallSession activeSession = new CallSession(
                    callId, conversationId, userId, peerId,
                    CallState.ACTIVE, null, NOW, NOW.plusSeconds(2), null, null);

            CallRegistry registry = mock(CallRegistry.class);
            when(registry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

            CallService service = mock(CallService.class);
            TestScheduledExecutor executor = new TestScheduledExecutor();
            CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, 15_000, executor);

            scheduler.checkDisconnectedUser(userId, () -> {});
            TestScheduledFuture<?> future = executor.futures.get(0);
            assertThat(future.isCancelled()).isFalse();

            // User reconnects
            scheduler.cancelScheduledDisconnect(userId);
            assertThat(future.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("2.3 Explicit call termination cancels pending disconnect timers for participants")
        void callTerminalCancelsPendingDisconnectTimers() {
            UUID callerId = UUID.randomUUID();
            UUID calleeId = UUID.randomUUID();
            UUID callId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();

            CallSession activeSession = new CallSession(
                    callId, conversationId, callerId, calleeId,
                    CallState.ACTIVE, null, NOW, NOW.plusSeconds(2), null, null);

            CallRegistry registry = mock(CallRegistry.class);
            when(registry.activeCallForUser(callerId)).thenReturn(Optional.of(activeSession));
            when(registry.find(callId)).thenReturn(Optional.of(activeSession));

            CallService service = mock(CallService.class);
            TestScheduledExecutor executor = new TestScheduledExecutor();
            CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, 15_000, executor);

            // Caller disconnected
            scheduler.checkDisconnectedUser(callerId, () -> {});
            TestScheduledFuture<?> callerFuture = executor.futures.get(0);
            assertThat(callerFuture.isCancelled()).isFalse();

            // Call explicitly ends (e.g. Callee hung up)
            scheduler.onCallTerminal(new CallTerminalEvent(callId));
            assertThat(callerFuture.isCancelled()).isTrue();
        }
    }

    // =========================================================================
    // FIX 3: Watermark Size Validation & Defensive Clamping
    // =========================================================================
    @Nested
    @DisplayName("Fix 3: Watermark Size Validation & DoS Clamping")
    class WatermarkClampingFixTests {

        @Mock private ConversationRepository conversationRepository;
        @Mock private ConversationMemberRepository memberRepository;
        @Mock private MessageRepository messageRepository;
        @Mock private MessageReactionRepository reactionRepository;

        private MessageService messageService;
        private final UUID userId = UUID.randomUUID();

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
        }

        @Test
        @DisplayName("3.1 Bean validation rejects watermark lists exceeding 100 items")
        void beanValidationRejectsWatermarksOver100() {
            ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
            Validator validator = factory.getValidator();

            List<SyncRequest.ConversationWatermark> validList = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                validList.add(new SyncRequest.ConversationWatermark(UUID.randomUUID(), (long) i));
            }
            SyncRequest validRequest = new SyncRequest(validList, 50, null);
            Set<ConstraintViolation<SyncRequest>> validViolations = validator.validate(validRequest);
            assertThat(validViolations).isEmpty();

            List<SyncRequest.ConversationWatermark> invalidList = new ArrayList<>(validList);
            invalidList.add(new SyncRequest.ConversationWatermark(UUID.randomUUID(), 101L));
            SyncRequest invalidRequest = new SyncRequest(invalidList, 50, null);
            Set<ConstraintViolation<SyncRequest>> invalidViolations = validator.validate(invalidRequest);
            assertThat(invalidViolations).isNotEmpty();
            assertThat(invalidViolations.iterator().next().getMessage()).contains("must not exceed 100");
        }

        @Test
        @DisplayName("3.2 MessageService defensively clamps watermark list to 100 items")
        void messageServiceClampsWatermarkListTo100() {
            int count = 150;
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
            SyncResponse response = messageService.syncDelta(userId, request);

            assertThat(response).isNotNull();
            assertThat(response.deltas()).hasSize(100);
        }

        @Test
        @DisplayName("3.3 Edited and deleted message results are bounded by limit in syncDelta")
        void editedAndDeletedQueriesBoundedByLimit() {
            UUID cId = UUID.randomUUID();
            Instant since = Instant.now().minusSeconds(60);
            when(memberRepository.findMemberFlags(eq(userId), any())).thenReturn(
                    List.of(new MemberFlagsRow(cId, false, null, false, false, null)));
            when(messageRepository.findRowsAfter(eq(cId), eq(10L), any(Pageable.class))).thenReturn(List.of());

            List<MessageRow> manyEdited = new ArrayList<>();
            List<UUID> manyDeleted = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                manyEdited.add(new MessageRow(UUID.randomUUID(), cId, (long) i, userId, "User", com.nexachat.message.Message.Type.TEXT, "txt", null, null, since, since, null));
                manyDeleted.add(UUID.randomUUID());
            }

            when(messageRepository.findEditedRowsSince(eq(cId), eq(10L), eq(since))).thenReturn(manyEdited);
            when(messageRepository.findDeletedMessageIdsSince(eq(cId), eq(10L), eq(since))).thenReturn(manyDeleted);
            when(messageRepository.findMaxSeqByConversationId(cId)).thenReturn(10L);

            SyncRequest request = new SyncRequest(List.of(new SyncRequest.ConversationWatermark(cId, 10L)), 50, since);
            SyncResponse response = messageService.syncDelta(userId, request);

            SyncResponse.ConversationDelta delta = response.deltas().get(0);
            assertThat(delta.editedMessages()).hasSize(50);
            assertThat(delta.deletedMessageIds()).hasSize(50);
        }
    }

    // =========================================================================
    // FIX 4: Token Re-registration & Multi-User Privacy
    // =========================================================================
    @Nested
    @DisplayName("Fix 4: Token Re-registration & Shared Device Privacy")
    class TokenReRegistrationPrivacyFixTests {

        @Mock private UserDeviceTokenRepository tokenRepository;
        @Mock private UserRepository userRepository;

        private DeviceTokenService deviceTokenService;
        private final UUID userBId = UUID.randomUUID();
        private final User userB = mock(User.class);

        @BeforeEach
        void setUp() {
            deviceTokenService = new DeviceTokenService(tokenRepository, userRepository);
            when(userRepository.findById(userBId)).thenReturn(Optional.of(userB));
        }

        @Test
        @DisplayName("4.1 Registering a shared-device token keeps the prior owner's row")
        void registeringSharedDeviceTokenKeepsPriorOwnerAssociation() {
            // Uniqueness is now (user_id, token): one physical device can hold a
            // row per account, so registering the token for user B must not evict
            // user A's push registration.
            String sharedToken = "shared-physical-device-token-xyz";

            when(tokenRepository.findByUserIdAndToken(userBId, sharedToken)).thenReturn(Optional.empty());
            when(tokenRepository.save(any(UserDeviceToken.class))).thenAnswer(inv -> inv.getArgument(0));

            DeviceTokenRequest request = new DeviceTokenRequest(sharedToken, "ANDROID", "Pixel 9");
            DeviceTokenResponse response = deviceTokenService.registerToken(userBId, request);

            assertThat(response).isNotNull();
            assertThat(response.token()).isEqualTo(sharedToken);

            verify(tokenRepository, org.mockito.Mockito.never()).deleteByToken(sharedToken);
            verify(tokenRepository).save(any(UserDeviceToken.class));
        }
    }

    // =========================================================================
    // FIX 5: STOMP Ack Rate Limiting
    // =========================================================================
    @Nested
    @DisplayName("Fix 5: STOMP Ack Rate Limiting")
    class StompAckRateLimitingFixTests {

        @Mock private JwtService jwtService;
        @Mock private UserRepository userRepository;
        @Mock private ConversationMemberRepository memberRepository;
        @Mock private SimpMessagingTemplate messaging;
        @Mock private MessageChannel channel;

        private RateLimitService rateLimitService;
        private StompAuthChannelInterceptor interceptor;

        private final UUID userId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            rateLimitService = new RateLimitService();
            interceptor = new StompAuthChannelInterceptor(
                    jwtService, userRepository, memberRepository, rateLimitService, messaging,
                    new ObjectMapper(), 30, 10, 60, 10, 3L, 10L
            );
        }

        @Test
        @DisplayName("5.1 /app/chat.delivery.ack is rate limited after exceeding allowance")
        void deliveryAckIsRateLimited() {
            for (int i = 0; i < 3; i++) {
                Message<?> msg = sendAck("/app/chat.delivery.ack");
                assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);
            }

            Message<?> excessMsg = sendAck("/app/chat.delivery.ack");
            assertThat(interceptor.preSend(excessMsg, channel)).isNull();
            verify(messaging).convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), any(WsErrorResponse.class));
        }

        @Test
        @DisplayName("5.2 /app/conversations/{id}/delivered is rate limited after exceeding allowance")
        void conversationDeliveredAckIsRateLimited() {
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);

            String dest = "/app/conversations/" + conversationId + "/delivered";
            for (int i = 0; i < 3; i++) {
                Message<?> msg = sendAck(dest);
                assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);
            }

            Message<?> excessMsg = sendAck(dest);
            assertThat(interceptor.preSend(excessMsg, channel)).isNull();
            verify(messaging).convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), any(WsErrorResponse.class));
        }

        private Message<?> sendAck(String destination) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination(destination);
            accessor.setUser(new StompUserPrincipal(userId));
            accessor.setLeaveMutable(true);
            return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        }
    }

    // =========================================================================
    // Test Scheduled Executor Helper
    // =========================================================================
    private static final class TestScheduledExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<TestScheduledFuture<?>> futures = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            delays.add(unit.toMillis(delay));
            TestScheduledFuture<?> f = new TestScheduledFuture<>(command, unit.toMillis(delay));
            futures.add(f);
            return f;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { tasks.add(command); delays.add(0L); }
    }

    private static final class TestScheduledFuture<V> implements ScheduledFuture<V> {
        private final Runnable task;
        private final long delayMs;
        private boolean cancelled = false;

        TestScheduledFuture(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.delayMs, o.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public V get() { return null; }

        @Override
        public V get(long timeout, TimeUnit unit) { return null; }
    }
}
