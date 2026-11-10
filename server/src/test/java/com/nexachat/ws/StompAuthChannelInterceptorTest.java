package com.nexachat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.RateLimitService;
import com.nexachat.auth.JwtService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.dto.WsErrorResponse;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Denial contract: {@code preSend} returns the message to let it through,
 * {@code null} to drop it — CONNECT authN, SUBSCRIBE/SEND authZ (plan §11.3.2).
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageChannel channel;
    @Mock
    private SimpMessagingTemplate messaging;

    private StompAuthChannelInterceptor interceptor;
    private RateLimitService rateLimitService;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
        interceptor = newInterceptor(rateLimitService);
    }

    private StompAuthChannelInterceptor newInterceptor(RateLimitService limits) {
        return new StompAuthChannelInterceptor(
                jwtService, userRepository, memberRepository, limits, messaging,
                new ObjectMapper(), 2, 60, 3, 10, 3, 10);
    }

    private Message<?> message(StompHeaderAccessor accessor) {
        return message(accessor, new byte[0]);
    }

    private Message<?> message(StompHeaderAccessor accessor, Object payload) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private Message<?> subscribeMessage(String destination, boolean authenticated) {
        return subscribeMessage(destination, authenticated ? userId : null);
    }

    private Message<?> subscribeMessage(String destination, UUID authenticatedUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-1");
        if (authenticatedUser != null) {
            accessor.setUser(new StompUserPrincipal(authenticatedUser));
        }
        return message(accessor);
    }

    private Message<?> sendMessage(String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        if (authenticated) {
            accessor.setUser(new StompUserPrincipal(userId));
        }
        return message(accessor);
    }

    private Message<?> sendMessage(String destination, UUID authenticatedUser, UUID clientMessageId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(new StompUserPrincipal(authenticatedUser));
        byte[] payload = ("{\"clientMessageId\":\"" + clientMessageId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return message(accessor, payload);
    }

    private Message<?> sendAckMessage(String destination, UUID authenticatedUser, UUID targetConversationId) {
        return sendAckMessage(destination, authenticatedUser,
                "{\"conversationId\":\"" + targetConversationId + "\",\"upToSeq\":1}");
    }

    private Message<?> sendAckMessage(String destination, UUID authenticatedUser, String payloadJson) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(new StompUserPrincipal(authenticatedUser));
        return message(accessor, payloadJson.getBytes(StandardCharsets.UTF_8));
    }

    // --------------------------------------------------------------- CONNECT

    @Test
    void connect_validToken_setsUserPrincipal() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer good-token");
        Message<?> message = message(accessor);
        when(jwtService.parseAccessTokenClaims("good-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, Instant.now()));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor.getUser()).isInstanceOf(StompUserPrincipal.class);
        assertThat(((StompUserPrincipal) resultAccessor.getUser()).userId()).isEqualTo(userId);
        assertThat(resultAccessor.getUser().getName()).isEqualTo(userId.toString());
    }

    @Test
    void connect_missingAuthorizationHeader_rejected() {
        Message<?> message = message(StompHeaderAccessor.create(StompCommand.CONNECT));

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void connect_malformedAuthorizationHeader_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Basic abc123");

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    @Test
    void connect_invalidToken_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer bad-token");
        when(jwtService.parseAccessTokenClaims("bad-token"))
                .thenThrow(new JwtException("signature invalid"));

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    @Test
    void connect_revokedAccessToken_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer revoked-token");
        // Issued before the cutoff stamped by the last logout/password change.
        when(jwtService.parseAccessTokenClaims("revoked-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, Instant.now().minusSeconds(60)));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, Instant.now())));

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    @Test
    void connect_tokenIssuedAfterTheCutoff_allowed() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer fresh-token");
        Instant cutoff = Instant.now().minusSeconds(60);
        when(jwtService.parseAccessTokenClaims("fresh-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, Instant.now()));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, cutoff)));

        assertThat(interceptor.preSend(message(accessor), channel)).isNotNull();
    }

    @Test
    void connectedSession_cannotSendAfterItsTokenIsRevoked() {
        Instant issuedAt = Instant.now().minusSeconds(60);
        StompHeaderAccessor connectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        connectAccessor.setNativeHeader("Authorization", "Bearer session-token");
        when(jwtService.parseAccessTokenClaims("session-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, issuedAt));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)))
                .thenReturn(Optional.of(new UserAuthValidity(userId, issuedAt.plusSeconds(1))));

        Message<?> connected = interceptor.preSend(message(connectAccessor), channel);
        assertThat(connected).isNotNull();
        StompHeaderAccessor connectedAccessor =
                MessageHeaderAccessor.getAccessor(connected, StompHeaderAccessor.class);

        StompHeaderAccessor sendAccessor = StompHeaderAccessor.create(StompCommand.SEND);
        sendAccessor.setUser(connectedAccessor.getUser());
        sendAccessor.setDestination("/app/conversations/" + conversationId + "/typing");

        assertThat(interceptor.preSend(message(sendAccessor), channel)).isNull();
    }

    @Test
    void expiredConnectedSession_cannotSubscribe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new StompUserPrincipal(
                userId, Instant.now().minusSeconds(120), Instant.now().minusSeconds(1)));
        accessor.setDestination("/user/queue/inbox");
        accessor.setSubscriptionId("sub-expired");

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    @Test
    void connect_nonexistentUser_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer good-token");
        when(jwtService.parseAccessTokenClaims("good-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, Instant.now()));

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    // -------------------------------------------------------------- SUBSCRIBE

    @Test
    void subscribe_conversationTopic_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_conversationTopic_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_conversationTopic_unauthenticated_rejected() {
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_userQueue_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/inbox", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_userErrors_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/errors", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_userPresence_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/presence", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_userCallQueue_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/calls", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_anotherUsersPresenceQueue_rejected() {
        Message<?> message = subscribeMessage(
                "/user/" + UUID.randomUUID() + "/queue/presence", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_unknownUserQueue_rejected() {
        Message<?> message = subscribeMessage("/user/queue/admin", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_userQueue_unauthenticated_rejected() {
        Message<?> message = subscribeMessage("/user/queue/inbox", false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_foreignTopic_rejected() {
        Message<?> message = subscribeMessage("/topic/other", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_malformedConversationTopic_rejected() {
        Message<?> message = subscribeMessage("/topic/conversations.not-a-uuid", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    // ------------------------------------------------------------------- SEND

    @Test
    void send_conversationDestination_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_typingAndRead_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        assertThat(interceptor.preSend(
                sendMessage("/app/conversations/" + conversationId + "/typing", true), channel))
                .isNotNull();
        assertThat(interceptor.preSend(
                sendMessage("/app/conversations/" + conversationId + "/read", true), channel))
                .isNotNull();
    }

    @Test
    void send_conversationDestination_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_callSignal_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = sendMessage("/app/calls/" + conversationId + "/signal", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_callSignal_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = sendMessage("/app/calls/" + conversationId + "/signal", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
        verify(messaging).convertAndSendToUser(userId.toString(), "/queue/errors",
                new WsErrorResponse("NOT_MEMBER", null));
    }

    @Test
    void send_unauthenticated_rejected() {
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_unknownAppDestination_rejected() {
        Message<?> message = sendMessage("/app/admin/shutdown", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_conversationDelivered_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/delivered", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_conversationDelivered_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/delivered", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_chatDeliveryAck_member_allowed() {
        lenient().when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        Message<?> message = sendAckMessage("/app/chat.delivery.ack", userId, conversationId);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_chatReadAck_member_allowed() {
        lenient().when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        Message<?> message = sendAckMessage("/app/chat.read.ack", userId, conversationId);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_chatDeliveryAck_nonMember_rejectedBeforeHandlerDispatch() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);
        Message<?> message = sendAckMessage("/app/chat.delivery.ack", userId, conversationId);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_chatReadAck_nonMember_rejectedBeforeHandlerDispatch() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);
        Message<?> message = sendAckMessage("/app/chat.read.ack", userId, conversationId);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_chatAckWithoutConversationId_reachesHandlerValidation() {
        assertThat(interceptor.preSend(sendMessage("/app/chat.delivery.ack", true), channel)).isNotNull();
        assertThat(interceptor.preSend(sendMessage("/app/chat.read.ack", true), channel)).isNotNull();
    }

    @Test
    void send_chatAckMalformedConversationId_continuesToPayloadConversion() {
        Message<?> delivery = sendAckMessage("/app/chat.delivery.ack", userId,
                "{\"conversationId\":\"not-a-uuid\",\"upToSeq\":1}");
        Message<?> read = sendAckMessage("/app/chat.read.ack", userId,
                "{\"conversationId\":\"not-a-uuid\",\"upToSeq\":1}");

        assertThat(interceptor.preSend(delivery, channel)).isSameAs(delivery);
        assertThat(interceptor.preSend(read, channel)).isSameAs(read);
    }

    @Test
    void send_chatAckUsesAcksRateLimitForMembers() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        for (int i = 0; i < 3; i++) {
            Message<?> allowed = sendAckMessage("/app/chat.delivery.ack", userId, conversationId);
            assertThat(interceptor.preSend(allowed, channel)).isSameAs(allowed);
        }

        Message<?> limited = sendAckMessage("/app/chat.read.ack", userId, conversationId);
        assertThat(interceptor.preSend(limited, channel)).isNull();
    }

    @Test
    void subscribe_matrixDestinationsEnforceTheirDeclaredPolicy() throws Exception {
        List<StompMatrixRoute> routes = readStompMatrix();
        UUID otherUser = UUID.randomUUID();
        int subscriptions = 0;

        for (StompMatrixRoute route : routes) {
            if (!route.kind().equals("SUBSCRIBE")) {
                continue;
            }
            subscriptions++;
            String destination = resolveMatrixDestination(route.destination());
            if (route.policy().equals("OWN_QUEUE")) {
                Message<?> ownQueue = subscribeMessage(destination, true);
                assertThat(interceptor.preSend(ownQueue, channel))
                        .as("own queue in STOMP matrix: " + route.destination())
                        .isSameAs(ownQueue);
            } else if (route.policy().equals("CONVERSATION_MEMBER")) {
                when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                        .thenReturn(true);
                when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, otherUser))
                        .thenReturn(false);
                Message<?> member = subscribeMessage(destination, userId);
                Message<?> outsider = subscribeMessage(destination, otherUser);
                assertThat(interceptor.preSend(member, channel))
                        .as("member topic in STOMP matrix: " + route.destination())
                        .isSameAs(member);
                assertThat(interceptor.preSend(outsider, channel))
                        .as("nonmember topic in STOMP matrix: " + route.destination())
                        .isNull();
            } else {
                throw new AssertionError("Unmapped STOMP SUBSCRIBE policy: " + route.policy());
            }
        }
        assertThat(subscriptions).as("STOMP subscription rows consumed by tests").isPositive();
    }

    @Test
    void send_matrixDestinationsEnforceTheirDeclaredPolicy() throws Exception {
        List<StompMatrixRoute> routes = readStompMatrix();
        UUID otherUser = UUID.randomUUID();
        int handlers = 0;

        for (StompMatrixRoute route : routes) {
            if (!route.kind().equals("HANDLER")) {
                continue;
            }
            handlers++;
            String destination = resolveMatrixDestination(route.destination());
            StompAuthChannelInterceptor routeInterceptor = newInterceptor(new RateLimitService());
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
            when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, otherUser)).thenReturn(false);
            Message<?> member;
            Message<?> outsider;
            if (route.policy().equals("PAYLOAD_CONVERSATION_MEMBER")) {
                member = sendAckMessage(destination, userId, conversationId);
                outsider = sendAckMessage(destination, otherUser, conversationId);
            } else if (route.policy().equals("CONVERSATION_MEMBER")) {
                member = sendMessage(destination, userId, UUID.randomUUID());
                outsider = sendMessage(destination, otherUser, UUID.randomUUID());
            } else {
                throw new AssertionError("Unmapped STOMP HANDLER policy: " + route.policy());
            }
            assertThat(routeInterceptor.preSend(member, channel))
                    .as("member SEND in STOMP matrix: " + route.destination())
                    .isSameAs(member);
            assertThat(routeInterceptor.preSend(outsider, channel))
                    .as("nonmember SEND in STOMP matrix: " + route.destination())
                    .isNull();
        }
        assertThat(handlers).as("STOMP handler rows consumed by tests").isPositive();
    }

    private static List<StompMatrixRoute> readStompMatrix() throws Exception {
        try (var stream = StompAuthChannelInterceptorTest.class.getResourceAsStream(
                "/stomp-authz-matrix.tsv")) {
            assertThat(stream).as("STOMP authorization matrix resource").isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<StompMatrixRoute> routes = new ArrayList<>();
                assertThat(reader.readLine()).isEqualTo("kind\tdestination\tpolicy\trole_scenario");
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split("\\t", -1);
                    assertThat(columns).hasSize(4);
                    assertThat(columns[3]).isNotBlank();
                    routes.add(new StompMatrixRoute(columns[0], columns[1], columns[2]));
                }
                return routes;
            }
        }
    }

    private String resolveMatrixDestination(String template) {
        return template.replaceAll("\\{[^}]+}", conversationId.toString());
    }

    @Test
    void send_messagesRateLimit_isPerUserAndReportsCorrelatedErrorWithoutDisconnecting() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID rejectedId = UUID.randomUUID();
        Message<?> first = sendMessage("/app/conversations/" + conversationId + "/messages", userId, firstId);
        Message<?> second = sendMessage("/app/conversations/" + conversationId + "/messages", userId, secondId);
        Message<?> rejected = sendMessage("/app/conversations/" + conversationId + "/messages", userId, rejectedId);

        assertThat(interceptor.preSend(first, channel)).isSameAs(first);
        assertThat(interceptor.preSend(second, channel)).isSameAs(second);
        assertThat(interceptor.preSend(rejected, channel)).isNull();
        verify(messaging).convertAndSendToUser(userId.toString(), "/queue/errors",
                new WsErrorResponse("RATE_LIMITED", rejectedId));
        // A reconnect and a new subscription do not reset the shared user bucket.
        StompHeaderAccessor reconnectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        reconnectAccessor.setNativeHeader("Authorization", "Bearer good-token");
        when(jwtService.parseAccessTokenClaims("good-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, Instant.now()));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        assertThat(interceptor.preSend(message(reconnectAccessor), channel)).isNotNull();
        assertThat(interceptor.preSend(subscribeMessage("/user/queue/inbox", true), channel)).isNotNull();
        assertThat(interceptor.preSend(sendMessage(
                "/app/conversations/" + conversationId + "/messages", userId, UUID.randomUUID()), channel))
                .isNull();

        UUID otherUser = UUID.randomUUID();
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, otherUser))
                .thenReturn(true);
        Message<?> otherUsersFirst = sendMessage(
                "/app/conversations/" + conversationId + "/messages", otherUser, UUID.randomUUID());
        assertThat(interceptor.preSend(otherUsersFirst, channel)).isSameAs(otherUsersFirst);
    }

    @Test
    void send_typingRateLimitIsSeparateAndCannotCreateMessageTraffic() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);

        for (int i = 0; i < 3; i++) {
            Message<?> typing = sendMessage("/app/conversations/" + conversationId + "/typing", userId,
                    UUID.randomUUID());
            assertThat(interceptor.preSend(typing, channel)).isSameAs(typing);
        }
        Message<?> excessTyping = sendMessage(
                "/app/conversations/" + conversationId + "/typing", userId, UUID.randomUUID());
        assertThat(interceptor.preSend(excessTyping, channel)).isNull();
        verify(messaging).convertAndSendToUser(userId.toString(), "/queue/errors",
                new WsErrorResponse("RATE_LIMITED", null));

        Message<?> chatMessage = sendMessage("/app/conversations/" + conversationId + "/messages",
                userId, UUID.randomUUID());
        assertThat(interceptor.preSend(chatMessage, channel)).isSameAs(chatMessage);
    }

    @Test
    void stompCommandWithoutCredentialsIsRejectedAndClosesTheSocket() throws Exception {
        UserRepository users = mock(UserRepository.class);
        StompSessionRegistry registry = new StompSessionRegistry(users);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("ws-1");
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator) registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompAuthChannelInterceptor gated = new StompAuthChannelInterceptor(
                jwtService, userRepository, memberRepository, rateLimitService, messaging,
                new ObjectMapper(), 2, 60, 3, 10, 3, 10, registry);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.STOMP);
        accessor.setSessionId("ws-1");

        assertThat(gated.preSend(message(accessor), channel)).isNull();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void disconnect_passesThrough() {
        Message<?> message = message(StompHeaderAccessor.create(StompCommand.DISCONNECT));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    private record StompMatrixRoute(String kind, String destination, String policy) {
    }
}
