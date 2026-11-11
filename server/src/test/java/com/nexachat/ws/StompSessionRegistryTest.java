package com.nexachat.ws;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void revocationAtIssuedAtCutoffBeforeSessionConnectedDoesNotBindSocket() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UUID userId = UUID.randomUUID();
        String sessionId = "same-second-ws-session";
        Instant cutoff = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, cutoff)));
        StompSessionRegistry registry = new StompSessionRegistry(users);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        registry.onAccessTokensRevoked(new AccessTokensRevokedEvent(userId));
        StompUserPrincipal principal = new StompUserPrincipal(
                userId, cutoff, cutoff.plusSeconds(300));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal);
        Message<byte[]> connectedMessage = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        registry.onSessionConnected(new SessionConnectedEvent(this, connectedMessage, principal));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.isAuthenticatedSession(sessionId)).isFalse();
        registry.shutdown();
    }

    @Test
    void revocationDuringConnectValidationCannotLeaveALateBoundSession() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UUID userId = UUID.randomUUID();
        String sessionId = "revoked-during-connect";
        CountDownLatch validityLookupStarted = new CountDownLatch(1);
        CountDownLatch resumeValidityLookup = new CountDownLatch(1);
        when(users.findAuthValidityById(userId)).thenAnswer(invocation -> {
            validityLookupStarted.countDown();
            if (!resumeValidityLookup.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to resume the captured pre-revocation lookup");
            }
            return Optional.of(new UserAuthValidity(userId, null));
        });

        StompSessionRegistry registry = new StompSessionRegistry(users);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> connect = executor.submit(() -> registry.onSessionConnected(connectedEvent(sessionId, principal)));
            assertThat(validityLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch revocationStarted = new CountDownLatch(1);
            CountDownLatch revocationCompleted = new CountDownLatch(1);
            AtomicReference<Thread> revocationThread = new AtomicReference<>();
            Future<?> revoke = executor.submit(() -> {
                revocationThread.set(Thread.currentThread());
                revocationStarted.countDown();
                registry.onAccessTokensRevoked(new AccessTokensRevokedEvent(userId));
                revocationCompleted.countDown();
            });
            assertThat(revocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRevocationContendedOrCompleted(revocationThread, revocationCompleted);

            // The repository returns the active validity snapshot read before
            // revocation, reproducing the reviewed interleaving deterministically.
            resumeValidityLookup.countDown();
            connect.get(5, TimeUnit.SECONDS);
            revoke.get(5, TimeUnit.SECONDS);

            assertThat(registry.isAuthenticatedSession(sessionId)).isFalse();
            verify(socket).close(CloseStatus.POLICY_VIOLATION);

            StompConversationMembershipOutboundInterceptor outbound =
                    new StompConversationMembershipOutboundInterceptor(
                            mock(ConversationMemberRepository.class), mock(SimpUserRegistry.class), registry);
            assertThat(outbound.preSend(conversationFrame(sessionId), mock(MessageChannel.class)))
                    .as("a socket revoked while CONNECT validation was in flight must not receive topic frames")
                    .isNull();
        } finally {
            resumeValidityLookup.countDown();
            executor.shutdownNow();
            registry.shutdown();
        }
    }

    @Test
    void tokenIssuedAtTheRevocationCutoffCannotBind() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UUID userId = UUID.randomUUID();
        String sessionId = "token-at-revocation-cutoff";
        Instant issuedAt = Instant.now().minusSeconds(30);
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, issuedAt)));

        StompSessionRegistry registry = new StompSessionRegistry(users);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, issuedAt, Instant.now().plusSeconds(300));
        registry.onSessionConnected(connectedEvent(sessionId, principal));

        assertThat(registry.isAuthenticatedSession(sessionId)).isFalse();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        registry.shutdown();
    }

    @Test
    void inboundFrameSizeIsCapped() {
        StompSessionRegistry registry = new StompSessionRegistry(mock(UserRepository.class));
        ReadableRegistration registration = new ReadableRegistration();

        registry.configureWebSocketTransport(registration);

        assertThat(registration.messageSizeLimit()).isEqualTo(StompSessionRegistry.MAX_MESSAGE_BYTES);
    }

    private static final class ReadableRegistration extends WebSocketTransportRegistration {
        int messageSizeLimit() {
            return getMessageSizeLimit();
        }
    }

    private static void awaitRevocationContendedOrCompleted(
            AtomicReference<Thread> revocationThread,
            CountDownLatch revocationCompleted) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Thread thread;
        do {
            thread = revocationThread.get();
            if (revocationCompleted.getCount() == 0 || thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("revocation neither completed nor contended with the in-flight CONNECT bind");
    }

    private static SessionConnectedEvent connectedEvent(String sessionId, StompUserPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal);
        Message<byte[]> connectedMessage = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(StompSessionRegistryTest.class, connectedMessage, principal);
    }

    private static Message<byte[]> conversationFrame(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setDestination("/topic/conversations." + UUID.randomUUID());
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void handshakeThatNeverAuthenticatesIsClosed() throws Exception {
        StompSessionRegistry registry = new StompSessionRegistry(mock(UserRepository.class));
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("pending");
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        registry.closeIfHandshakeIncomplete("pending");

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void shutdownClosesOpenSocketsWithGoingAway() throws Exception {
        UserRepository users = mock(UserRepository.class);
        StompSessionRegistry registry = new StompSessionRegistry(users);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("shutdown-session");
        when(socket.isOpen()).thenReturn(true);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);
        registry.shutdown();

        verify(socket).close(CloseStatus.GOING_AWAY);
    }
}
