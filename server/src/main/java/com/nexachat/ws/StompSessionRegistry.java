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
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
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

    /** Inbound STOMP frame cap. Larger frames are closed by the WebSocket transport. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;
    /** Authenticated sockets one account may hold at once. */
    public static final int MAX_SESSIONS_PER_USER = 32;
    /** How long a raw socket may sit before a successful STOMP CONNECT. */
    static final long HANDSHAKE_DEADLINE_SECONDS = 10;
    /** Fixed lock stripes serialize each user's CONNECT bind with revocation without retaining user locks forever. */
    private static final int USER_LIFECYCLE_LOCK_STRIPES = 64;
    /** Fixed lock stripes serialize a raw socket write with its revocation close. */
    private static final int SESSION_TRANSPORT_LOCK_STRIPES = 64;

    private final UserRepository userRepository;
    private final Object[] userLifecycleLocks = createUserLifecycleLocks();
    private final Object[] sessionTransportLocks = createSessionTransportLocks();
    private final ConcurrentHashMap<String, WebSocketSession> socketsBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> userBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final Set<String> closedSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Instant> expiresBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> handshakeDeadlines = new ConcurrentHashMap<>();
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
        registration.setMessageSizeLimit(MAX_MESSAGE_BYTES);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(10_000);
        registration.addDecoratorFactory(this);
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                String sessionId = session.getId();
                // Broker writes may be queued after outbound channel checks, so guard the raw socket write too.
                WebSocketSession guardedSession = new WebSocketSessionDecorator(session) {
                    @Override
                    public void sendMessage(WebSocketMessage<?> message) throws IOException {
                        boolean expired = false;
                        synchronized (sessionTransportLock(sessionId)) {
                            if (closedSessions.contains(sessionId)) {
                                return;
                            }
                            Instant expiresAt = expiresBySession.get(sessionId);
                            if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
                                expired = true;
                            } else {
                                super.sendMessage(message);
                            }
                        }
                        if (expired) {
                            closeSession(sessionId);
                        }
                    }
                };
                socketsBySession.put(sessionId, guardedSession);
                scheduleHandshakeDeadline(sessionId);
                try {
                    super.afterConnectionEstablished(guardedSession);
                } catch (Exception e) {
                    cancelHandshakeDeadline(sessionId);
                    socketsBySession.remove(sessionId, guardedSession);
                    throw e;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                String sessionId = session.getId();
                synchronized (sessionTransportLock(sessionId)) {
                    socketsBySession.remove(sessionId);
                    unbind(sessionId);
                }
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    closedSessions.remove(sessionId);
                }
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
        synchronized (userLifecycleLock(principal.userId())) {
            WebSocketSession socket = socketsBySession.get(sessionId);
            if (socket == null || !socket.isOpen()) {
                return;
            }
            if (isExpired(principal) || (principal.issuedAt() != null && isRevoked(principal))) {
                closeSession(sessionId);
                return;
            }
            if (!canOpenSession(principal.userId())) {
                closeSession(sessionId);
                return;
            }
            bind(sessionId, principal.userId(), principal.expiresAt());
            if (!socket.isOpen()) {
                closeSession(sessionId);
            }
        }
    }

    @EventListener
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        synchronized (sessionTransportLock(sessionId)) {
            closedSessions.add(sessionId);
            unbind(sessionId);
            socketsBySession.remove(sessionId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessTokensRevoked(AccessTokensRevokedEvent event) {
        synchronized (userLifecycleLock(event.userId())) {
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
    }

    @PreDestroy
    public void shutdown() {
        for (String sessionId : Set.copyOf(socketsBySession.keySet())) {
            try {
                WebSocketSession socket = socketsBySession.get(sessionId);
                if (socket != null && socket.isOpen()) {
                    socket.close(CloseStatus.GOING_AWAY);
                }
            } catch (IOException failure) {
                log.warn("Could not close STOMP session during shutdown");
            } finally {
                unbind(sessionId);
                socketsBySession.remove(sessionId);
            }
        }
        expiryScheduler.shutdownNow();
    }

    /** False once {@code userId} already holds {@link #MAX_SESSIONS_PER_USER} sockets. */
    public boolean canOpenSession(UUID userId) {
        synchronized (userLifecycleLock(userId)) {
            Set<String> sessions = sessionsByUser.get(userId);
            return sessions == null || sessions.size() < MAX_SESSIONS_PER_USER;
        }
    }

    /** Closes a socket whose handshake or executor-time authentication was rejected. */
    public void closeHandshake(String sessionId) {
        if (sessionId == null) {
            return;
        }
        closeSession(sessionId);
    }

    /** Closes the socket when CONNECT never completed. Package-visible for tests. */
    void closeIfHandshakeIncomplete(String sessionId) {
        cancelHandshakeDeadline(sessionId);
        if (sessionId != null && !userBySession.containsKey(sessionId)) {
            closeSession(sessionId);
        }
    }

    /** True while the STOMP session is still bound to a user with a live access token. */
    public boolean isAuthenticatedSession(String sessionId) {
        UUID userId = userBySession.get(sessionId);
        if (userId == null) {
            return false;
        }
        synchronized (userLifecycleLock(userId)) {
            if (!userId.equals(userBySession.get(sessionId))) {
                return false;
            }
            Instant expiresAt = expiresBySession.get(sessionId);
            if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
                closeSession(sessionId);
                return false;
            }
            return true;
        }
    }

    private static boolean isExpired(StompUserPrincipal principal) {
        return principal.expiresAt() != null && !Instant.now().isBefore(principal.expiresAt());
    }

    /** The user bound to a session, or {@code null} for an unknown/unbound session. */
    public java.util.UUID userIdForSession(String sessionId) {
        return sessionId == null ? null : userBySession.get(sessionId);
    }

    private boolean isRevoked(StompUserPrincipal principal) {
        return userRepository.findAuthValidityById(principal.userId())
                .map(validity -> {
                    Instant cutoff = validity.tokensValidAfter();
                    return cutoff != null && !principal.issuedAt().isAfter(cutoff);
                })
                .orElse(true);
    }

    private static Object[] createUserLifecycleLocks() {
        Object[] locks = new Object[USER_LIFECYCLE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object userLifecycleLock(UUID userId) {
        return userLifecycleLocks[Math.floorMod(userId.hashCode(), userLifecycleLocks.length)];
    }

    private static Object[] createSessionTransportLocks() {
        Object[] locks = new Object[SESSION_TRANSPORT_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object sessionTransportLock(String sessionId) {
        return sessionTransportLocks[Math.floorMod(sessionId.hashCode(), sessionTransportLocks.length)];
    }

    private void bind(String sessionId, UUID userId, Instant expiresAt) {
        synchronized (sessionTransportLock(sessionId)) {
            if (closedSessions.contains(sessionId)) {
                return;
            }
            cancelHandshakeDeadline(sessionId);
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
    }

    private void unbind(String sessionId) {
        cancelHandshakeDeadline(sessionId);
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

    private void scheduleHandshakeDeadline(String sessionId) {
        ScheduledFuture<?> task = expiryScheduler.schedule(
                () -> closeIfHandshakeIncomplete(sessionId),
                HANDSHAKE_DEADLINE_SECONDS,
                TimeUnit.SECONDS);
        ScheduledFuture<?> previous = handshakeDeadlines.put(sessionId, task);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelHandshakeDeadline(String sessionId) {
        ScheduledFuture<?> task = handshakeDeadlines.remove(sessionId);
        if (task != null) {
            task.cancel(false);
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
        WebSocketSession socket;
        synchronized (sessionTransportLock(sessionId)) {
            closedSessions.add(sessionId);
            unbind(sessionId);
            socket = socketsBySession.get(sessionId);
        }
        closeSocket(socket);
    }

    private void closeSocket(WebSocketSession socket) {
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
