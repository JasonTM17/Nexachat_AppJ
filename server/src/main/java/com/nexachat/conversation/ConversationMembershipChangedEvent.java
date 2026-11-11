package com.nexachat.conversation;

import java.util.List;
import java.util.UUID;

/** Current-state projection trigger emitted after a conversation membership mutation. */
public record ConversationMembershipChangedEvent(
        UUID conversationId,
        List<UUID> removedUserIds) {

    public ConversationMembershipChangedEvent {
        removedUserIds = List.copyOf(removedUserIds);
    }
}
