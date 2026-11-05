package com.nexachat.presence;

import com.nexachat.ws.StompUserPrincipal;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class PresenceSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(PresenceSessionRegistry.class);
    private final PresenceService presenceService;
    private final PresenceSessionState sessionState;
    private final long coalesceMillis;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, UserSessions> sessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> userBySession = new ConcurrentHashMap<>();

    @Autowired
    public PresenceSessionRegistry(PresenceService presenceService,
                                   PresenceSessionState sessionState,
                                   @Value("${nexachat.presence.disconnect-coalesce-ms:5000}") long coalesceMillis) {
        this(presenceService, sessionState, coalesceMillis,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "nexachat-presence-coalescer");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    PresenceSessionRegistry(PresenceService presenceService,
                           PresenceSessionState sessionState,
                           long coalesceMillis,
                           ScheduledExecutorService scheduler) {
        this.presenceService = presenceService;
        this.sessionState = sessionState;
        this.coalesceMillis = coalesceMillis;
        this.scheduler = scheduler;
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        UUID userId = userId(accessor.getUser());
        String sessionId = accessor.getSessionId();
        if (userId != null && sessionId != null) {
            connect(sessionId, userId);
        }
    }

    @EventListener
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        disconnect(event.getSessionId());
    }

    void connect(String sessionId, UUID userId) {
        UUID existingUser = userBySession.putIfAbsent(sessionId, userId);
        if (existingUser != null) {
            return;
        }
        UserSessions userSessions = sessionsByUser.computeIfAbsent(userId, ignored -> new UserSessions());
        synchronized (userSessions) {
            boolean firstSession = userSessions.sessionIds.isEmpty();
            boolean coalesced = userSessions.pendingOffline != null;
            if (coalesced) {
                userSessions.pendingOffline.cancel(false);
                userSessions.pendingOffline = null;
                userSessions.generation++;
            }
            userSessions.sessionIds.add(sessionId);
            sessionState.setOnline(userId, true);
            if (firstSession && !coalesced) {
                presenceService.connected(userId);
            }
        }
    }

    void disconnect(String sessionId) {
        UUID userId = userBySession.remove(sessionId);
        if (userId == null) {
            return;
        }
        UserSessions userSessions = sessionsByUser.get(userId);
        if (userSessions == null) {
            return;
        }
        synchronized (userSessions) {
            if (!userSessions.sessionIds.remove(sessionId) || !userSessions.sessionIds.isEmpty()) {
                return;
            }
            sessionState.setOnline(userId, false);
            Instant lastSeenAt = presenceService.recordLastSeen(userId);
            long generation = ++userSessions.generation;
            userSessions.pendingOffline = scheduler.schedule(
                    () -> publishOfflineIfStillDisconnected(userId, userSessions, generation, lastSeenAt),
                    coalesceMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void publishOfflineIfStillDisconnected(UUID userId, UserSessions userSessions,
                                                    long generation, Instant lastSeenAt) {
        synchronized (userSessions) {
            if (!userSessions.sessionIds.isEmpty() || userSessions.pendingOffline == null
                    || userSessions.generation != generation) {
                return;
            }
            userSessions.pendingOffline = null;
            presenceService.disconnected(userId, lastSeenAt);
        }
    }

    private static UUID userId(Principal principal) {
        return principal instanceof StompUserPrincipal user ? user.userId() : null;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class UserSessions {
        private final Set<String> sessionIds = new HashSet<>();
        private ScheduledFuture<?> pendingOffline;
        private long generation;
    }
}
