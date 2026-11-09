package com.nexachat.ws;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.user.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks raw WebSocket sessions so a committed token revocation can close them.
 * A session is bound to its authenticated user only for the lifetime of the
 * access token that opened it: the registry closes the socket at the token's
 * expiry, and the outbound gate refuses frames for a session whose binding has
 * already expired.
 */
@Component
public class StompSessionRegistry implements WebSocketHandlerDecoratorFactory, WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StompSessionRegistry.class);

    private final UserRepository userRepository;
    private final ConcurrentHashMap<String, WebSocketSession> socketsBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> userBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> expiresBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nexachat-stomp-expiry");
                thread.setDaemon(true);
                return thread;
            });

    public StompSessionRegistry(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(this);
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                socketsBySession.put(session.getId(), session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception e) {
                    socketsBySession.remove(session.getId(), session);
                    throw e;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                socketsBySession.remove(session.getId(), session);
                unbind(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null || !(accessor.getUser() instanceof StompUserPrincipal principal)) {
            return;
        }
        WebSocketSession socket = socketsBySession.get(sessionId);
        if (socket == null || !socket.isOpen()) {
            return;
        }
        if (isExpired(principal) || (principal.issuedAt() != null && isRevoked(principal))) {
            closeSession(sessionId);
            return;
        }
        bind(sessionId, principal.userId(), principal.expiresAt());
    }

    @EventListener
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        unbind(event.getSessionId());
        socketsBySession.remove(event.getSessionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessTokensRevoked(AccessTokensRevokedEvent event) {
        Set<String> sessionIds = sessionsByUser.remove(event.userId());
        if (sessionIds == null) {
            return;
        }
        for (String sessionId : Set.copyOf(sessionIds)) {
            if (event.userId().equals(userBySession.get(sessionId))) {
                closeSession(sessionId);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        expiryScheduler.shutdownNow();
    }

    /** True while the STOMP session is still bound to a user with a live access token. */
    public boolean isAuthenticatedSession(String sessionId) {
        if (!userBySession.containsKey(sessionId)) {
            return false;
        }
        Instant expiresAt = expiresBySession.get(sessionId);
        if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
            closeSession(sessionId);
            return false;
        }
        return true;
    }

    private static boolean isExpired(StompUserPrincipal principal) {
        return principal.expiresAt() != null && !Instant.now().isBefore(principal.expiresAt());
    }

    private boolean isRevoked(StompUserPrincipal principal) {
        return userRepository.findAuthValidityById(principal.userId())
                .map(validity -> {
                    Instant cutoff = validity.tokensValidAfter();
                    return cutoff != null && principal.issuedAt().isBefore(cutoff);
                })
                .orElse(true);
    }

    private void bind(String sessionId, UUID userId, Instant expiresAt) {
        UUID previous = userBySession.putIfAbsent(sessionId, userId);
        if (previous != null) {
            return;
        }
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
        if (expiresAt != null) {
            expiresBySession.put(sessionId, expiresAt);
            scheduleExpiry(sessionId, expiresAt);
        }
    }

    private void unbind(String sessionId) {
        cancelExpiry(sessionId);
        expiresBySession.remove(sessionId);
        UUID userId = userBySession.remove(sessionId);
        if (userId == null) {
            return;
        }
        Set<String> sessions = sessionsByUser.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId, sessions);
            }
        }
    }

    private void scheduleExpiry(String sessionId, Instant expiresAt) {
        long delayMillis = Math.max(1L, Duration.between(Instant.now(), expiresAt).toMillis());
        ScheduledFuture<?> task = expiryScheduler.schedule(() -> {
            expiryTasks.remove(sessionId);
            closeSession(sessionId);
        }, delayMillis, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = expiryTasks.put(sessionId, task);
        if (previous != null) {
            previous.cancel(false);
        }
        if (task.isDone()) {
            expiryTasks.remove(sessionId, task);
        }
    }

    private void cancelExpiry(String sessionId) {
        ScheduledFuture<?> task = expiryTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void closeSession(String sessionId) {
        unbind(sessionId);
        closeSocket(sessionId);
    }

    private void closeSocket(String sessionId) {
        WebSocketSession socket = socketsBySession.get(sessionId);
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            log.warn("Could not close an expired or revoked STOMP session");
        }
    }
}
