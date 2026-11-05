package com.nexachat.presence;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory active-user view used by inbox hydration on this single node. */
@Component
public class PresenceSessionState {

    private final Set<UUID> onlineUsers = ConcurrentHashMap.newKeySet();

    public boolean isOnline(UUID userId) {
        return onlineUsers.contains(userId);
    }

    void setOnline(UUID userId, boolean online) {
        if (online) {
            onlineUsers.add(userId);
        } else {
            onlineUsers.remove(userId);
        }
    }
}
