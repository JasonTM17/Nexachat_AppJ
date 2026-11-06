package com.nexachat.call;

import com.nexachat.presence.PresenceSessionState;
import com.nexachat.ws.StompUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CallDisconnectListener {

    private static final Logger log = LoggerFactory.getLogger(CallDisconnectListener.class);

    private final PresenceSessionState presenceState;
    private final CallTimeoutScheduler scheduler;
    private final CallService callService;
    private final ConcurrentHashMap<UUID, Integer> persistenceRetries = new ConcurrentHashMap<>();

    public CallDisconnectListener(PresenceSessionState presenceState,
                                  CallTimeoutScheduler scheduler,
                                  CallService callService) {
        this.presenceState = presenceState;
        this.scheduler = scheduler;
        this.callService = callService;
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        UUID userId = userId(accessor.getUser());
        if (userId == null) {
            return;
        }
        scheduler.checkDisconnectedUser(userId, () -> {
            if (!presenceState.isOnline(userId)) {
                retryDisconnect(userId);
            }
        });
    }

    private void retryDisconnect(UUID userId) {
        if (presenceState.isOnline(userId)) {
            persistenceRetries.remove(userId);
            return;
        }
        try {
            callService.disconnect(userId);
            persistenceRetries.remove(userId);
        } catch (RuntimeException failure) {
            int attempt = persistenceRetries.merge(userId, 1, Integer::sum);
            if (attempt > CallTimeoutScheduler.MAX_PERSISTENCE_RETRIES) {
                persistenceRetries.remove(userId);
                log.warn("Stopped retrying call disconnect persistence for {} after {} failed attempts",
                        userId, attempt);
                return;
            }
            scheduler.retryDisconnectedUser(() -> retryDisconnect(userId), attempt);
        }
    }

    private static UUID userId(Principal principal) {
        return principal instanceof StompUserPrincipal user ? user.userId() : null;
    }
}
