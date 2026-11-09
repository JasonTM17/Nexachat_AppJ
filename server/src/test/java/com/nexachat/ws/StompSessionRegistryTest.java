package com.nexachat.ws;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompSessionRegistryTest {

    @Test
    void committedRevocationClosesTrackedSocketAndUnbindsIt() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UUID userId = UUID.randomUUID();
        String sessionId = "ws-session";
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        StompSessionRegistry registry = new StompSessionRegistry(users);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal);
        Message<byte[]> connectedMessage = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        registry.onSessionConnected(new SessionConnectedEvent(this, connectedMessage, principal));

        assertThat(registry.isAuthenticatedSession(sessionId)).isTrue();

        registry.onAccessTokensRevoked(new AccessTokensRevokedEvent(userId));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.isAuthenticatedSession(sessionId)).isFalse();
    }
}
