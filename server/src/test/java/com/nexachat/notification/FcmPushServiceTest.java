package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.call.CallState;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmPushServiceTest {

    @Mock
    private UserDeviceTokenRepository tokenRepository;
    @Mock
    private PresenceSessionState presenceState;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private CallRegistry callRegistry;
    @Mock
    private UserRepository userRepository;

    private MockFcmGateway mockGateway;
    private DefaultFcmPushService pushService;
    private NotificationEventListener eventListener;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockGateway = new MockFcmGateway();
        pushService = new DefaultFcmPushService(tokenRepository, mockGateway);
        eventListener = new NotificationEventListener(
                pushService, memberRepository, presenceState, callRegistry, userRepository);
    }

    @Test
    void newMessageNotificationPayloadIsLightweightUnder500Bytes() throws Exception {
        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(user, "fcm-token-abc-123", UserDeviceToken.Platform.ANDROID, "Pixel 8");
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

        UUID messageId = UUID.randomUUID();
        pushService.sendNewMessageNotification(
                userId, conversationId, messageId, 105L, "Nguyễn Văn A"
        );

        List<MockFcmGateway.SentPush> sent = mockGateway.getSentPushes();
        assertThat(sent).hasSize(1);
        MockFcmGateway.SentPush push = sent.get(0);
        assertThat(push.token()).isEqualTo("fcm-token-abc-123");
        assertThat(push.priority()).isEqualTo("HIGH");
        assertThat(push.data().get("type")).isEqualTo("NEW_MESSAGE");
        assertThat(push.data().get("conversationId")).isEqualTo(conversationId.toString());
        assertThat(push.data().get("messageId")).isEqualTo(messageId.toString());
        assertThat(push.data().get("seq")).isEqualTo("105");

        byte[] jsonBytes = objectMapper.writeValueAsString(push.data()).getBytes(StandardCharsets.UTF_8);
        assertThat(jsonBytes.length).as("FCM pointer payload must be < 500 bytes").isLessThan(500);
    }

    @Test
    void newMessageNotificationNeverCarriesMessageContent() {
        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(user, "fcm-token-content", UserDeviceToken.Platform.ANDROID, "Pixel 8");
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

        String secret = "TOC-MESSAGE-CONTENT-MUST-NOT-LEAVE-THE-SERVER";
        pushService.sendNewMessageNotification(
                userId, conversationId, UUID.randomUUID(), 1L, "Alice"
        );

        MockFcmGateway.SentPush push = mockGateway.getSentPushes().get(0);
        assertThat(push.data()).doesNotContainKey("snippet");
        assertThat(push.data().values())
                .as("push payload must not contain message plaintext")
                .noneMatch(value -> value != null && value.contains(secret));
    }

    @Test
    void expiredTokenCleanupDoesNotLogTheRegistrationToken() {
        mockGateway.setSimulateUnregistered(true);

        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(
                user, "SECRET-REGISTRATION-TOKEN", UserDeviceToken.Platform.ANDROID, "Old Phone");
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefaultFcmPushService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            pushService.sendNewMessageNotification(
                    userId, conversationId, UUID.randomUUID(), 1L, "Alice");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .as("registration token must never reach the log")
                .noneMatch(event -> event.getFormattedMessage().contains("SECRET-REGISTRATION-TOKEN"));
    }

    @Test
    void mockGatewayDoesNotLogTheRegistrationToken() {
        MockFcmGateway gateway = new MockFcmGateway();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MockFcmGateway.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            gateway.sendDataPush("SECRET-MOCK-TOKEN", Map.of("type", "NEW_MESSAGE"), "HIGH", 1L);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .as("registration token must never reach the log")
                .noneMatch(event -> event.getFormattedMessage().contains("SECRET-MOCK-TOKEN"));
    }

    @Test
    void callInviteNotificationPayloadIsLightweightUnder500Bytes() throws Exception {
        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(user, "fcm-token-call-456", UserDeviceToken.Platform.ANDROID, "Samsung S24");
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

        UUID callId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        long nowMs = System.currentTimeMillis();

        pushService.sendCallInviteNotification(
                userId, callId, conversationId, callerId, "Trần Thị B", "AUDIO", nowMs
        );

        List<MockFcmGateway.SentPush> sent = mockGateway.getSentPushes();
        assertThat(sent).hasSize(1);
        MockFcmGateway.SentPush push = sent.get(0);
        assertThat(push.data().get("type")).isEqualTo("CALL_INVITE");
        assertThat(push.data().get("callId")).isEqualTo(callId.toString());
        assertThat(push.data().get("callerName")).isEqualTo("Trần Thị B");
        assertThat(push.data().get("callType")).isEqualTo("AUDIO");

        byte[] jsonBytes = objectMapper.writeValueAsString(push.data()).getBytes(StandardCharsets.UTF_8);
        assertThat(jsonBytes.length).as("Call invite payload must be < 500 bytes").isLessThan(500);
    }

    @Test
    void unregistersTokenWhenGatewayReturnsUnregistered() {
        mockGateway.setSimulateUnregistered(true);

        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(user, "stale-fcm-token", UserDeviceToken.Platform.ANDROID, "Old Phone");
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(token));

        pushService.sendNewMessageNotification(
                userId, conversationId, UUID.randomUUID(), 1L, "Alice"
        );

        verify(tokenRepository).deleteByToken("stale-fcm-token");
    }

    @Test
    void notificationEventListenerOnlyPushesToOfflineMembers() {
        UUID onlineMember = UUID.randomUUID();
        UUID offlineMember = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(senderId, onlineMember, offlineMember));
        when(presenceState.isOnline(onlineMember)).thenReturn(true);
        when(presenceState.isOnline(offlineMember)).thenReturn(false);

        User user = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(user, "offline-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(offlineMember)).thenReturn(List.of(token));

        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 1L, senderId, "Sender",
                Message.Type.TEXT.name(), "Test message", UUID.randomUUID(), null,
                null, List.of(), List.of(), Instant.now(), null, null
        );

        eventListener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        List<MockFcmGateway.SentPush> sent = mockGateway.getSentPushes();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).token()).isEqualTo("offline-token");
    }

    @Test
    void notificationEventListenerPushesCallInviteToOfflineCallee() {
        UUID callerId = UUID.randomUUID();
        UUID calleeId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();

        CallSession session = CallSession.ringing(callId, conversationId, callerId, calleeId, Instant.now());
        when(callRegistry.find(callId)).thenReturn(Optional.of(session));
        when(presenceState.isOnline(calleeId)).thenReturn(false);

        User caller = mock(User.class);
        when(caller.getDisplayName()).thenReturn("John Caller");
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        User callee = mock(User.class);
        UserDeviceToken calleeToken = UserDeviceToken.create(callee, "callee-fcm-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(calleeId)).thenReturn(List.of(calleeToken));

        eventListener.onCallRinging(new CallRingingEvent(callId));

        List<MockFcmGateway.SentPush> sent = mockGateway.getSentPushes();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).token()).isEqualTo("callee-fcm-token");
        assertThat(sent.get(0).data().get("type")).isEqualTo("CALL_INVITE");
        assertThat(sent.get(0).data().get("callerName")).isEqualTo("John Caller");
    }
}
