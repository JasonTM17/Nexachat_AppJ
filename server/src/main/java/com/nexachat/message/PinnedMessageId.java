package com.nexachat.message;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link PinnedMessage}: {@code (conversation_id, message_id)}.
 * Field names match the entity's id attributes, field types are the target PK
 * types — the {@code ConversationMemberId} pattern.
 */
public class PinnedMessageId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID conversation;
    private UUID message;

    protected PinnedMessageId() {
        // JPA only
    }

    public PinnedMessageId(UUID conversation, UUID message) {
        this.conversation = conversation;
        this.message = message;
    }

    public UUID getConversation() {
        return conversation;
    }

    public UUID getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PinnedMessageId other)) {
            return false;
        }
        return Objects.equals(conversation, other.conversation)
                && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversation, message);
    }
}
