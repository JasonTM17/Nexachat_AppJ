package com.nexachat.ws;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An access token that expires after CONNECT must not leave the socket
 * authenticated forever. The registry enforces the expiry on the outbound
 * gate and closes the socket at the token's expiry, so the user also stops
 * being reported online.
 */
class StompSessionExpiryTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
    private final SimpUserRegistry userRegistry = mock(SimpUserRegistry.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final String sessionId = "expiring-session";

    @Test
    void expiredSessionMustNotReceiveConversationTopicFrames() throws Exception {
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));

        StompSessionRegistry registry = new StompSessionRegistry(users);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        Instant issuedAt = Instant.now().minusSeconds(30);
        Instant expiresAt = Instant.now().plusSeconds(2);
        StompUserPrincipal principal = new StompUserPrincipal(userId, issuedAt, expiresAt);
        registry.onSessionConnected(connectedEvent(principal));

        SimpSession simpSession = mock(SimpSession.class);
        when(simpSession.getId()).thenReturn(sessionId);
        SimpUser simpUser = mock(SimpUser.class);
        when(simpUser.getName()).thenReturn(userId.toString());
        when(simpUser.getSessions()).thenReturn(Set.of(simpSession));
        when(userRegistry.getUsers()).thenReturn(Set.of(simpUser));
        when(members.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);

        StompConversationMembershipOutboundInterceptor outbound =
                new StompConversationMembershipOutboundInterceptor(members, userRegistry, registry);

        // The token is still valid at bind time.
        assertThat(registry.isAuthenticatedSession(sessionId)).isTrue();

        // Deliberate wall-clock wait: token expiry is the behaviour under test
        // and the registry derives its schedule from the real clock.
        Thread.sleep(2_300L);

        assertThat(outbound.preSend(topicFrame(), channel))
                .as("an expired access token must not retain an authenticated receive path")
                .isNull();
        verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void alreadyExpiredTokenNeverBecomesAnAuthenticatedSession() throws Exception {
        StompSessionRegistry registry = new StompSessionRegistry(users);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        registry.onSessionConnected(connectedEvent(principal));

        assertThat(registry.isAuthenticatedSession(sessionId)).isFalse();
        verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION);
    }

    private SessionConnectedEvent connectedEvent(StompUserPrincipal principal) {
        StompHeaderAccessor connected = StompHeaderAccessor.create(StompCommand.CONNECTED);
        connected.setSessionId(sessionId);
        connected.setUser(principal);
        Message<byte[]> connectedMessage = MessageBuilder.createMessage(
                new byte[0], connected.getMessageHeaders());
        return new SessionConnectedEvent(this, connectedMessage, principal);
    }

    private Message<byte[]> topicFrame() {
        StompHeaderAccessor frame = StompHeaderAccessor.create(StompCommand.MESSAGE);
        frame.setSessionId(sessionId);
        frame.setDestination("/topic/conversations." + conversationId);
        return MessageBuilder.createMessage(new byte[0], frame.getMessageHeaders());
    }
}
