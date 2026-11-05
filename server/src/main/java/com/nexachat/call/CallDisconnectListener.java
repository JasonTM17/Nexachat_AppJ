package com.nexachat.call;

import com.nexachat.presence.PresenceSessionState;
import com.nexachat.ws.StompUserPrincipal;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.UUID;

@Component
public class CallDisconnectListener {

    private final PresenceSessionState presenceState;
    private final CallTimeoutScheduler scheduler;
    private final CallService callService;

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
                callService.disconnect(userId);
            }
        });
    }

    private static UUID userId(Principal principal) {
        return principal instanceof StompUserPrincipal user ? user.userId() : null;
    }
}
