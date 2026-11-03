package com.nexachat.conversation;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link ConversationMember}: {@code (conversation_id, user_id)}.
 * Same {@code @IdClass}-on-relationship style as {@code ContactId} — field names
 * match the entity's id attributes, field types are the target PK types.
 */
public class ConversationMemberId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID conversation;
    private UUID user;

    protected ConversationMemberId() {
        // JPA only
    }

    public ConversationMemberId(UUID conversation, UUID user) {
        this.conversation = conversation;
        this.user = user;
    }

    public UUID getConversation() {
        return conversation;
    }

    public UUID getUser() {
        return user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConversationMemberId other)) {
            return false;
        }
        return Objects.equals(conversation, other.conversation) && Objects.equals(user, other.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversation, user);
    }
}
