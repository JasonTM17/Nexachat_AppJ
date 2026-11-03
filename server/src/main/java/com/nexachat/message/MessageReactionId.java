package com.nexachat.message;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link MessageReaction}: {@code (message_id, user_id, emoji)}.
 * Same {@code @IdClass}-on-relationship style as {@code ConversationMemberId} —
 * field names match the entity's id attributes, field types are the target PK
 * types (the {@code emoji} column maps directly).
 */
public class MessageReactionId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID message;
    private UUID user;
    private String emoji;

    protected MessageReactionId() {
        // JPA only
    }

    public MessageReactionId(UUID message, UUID user, String emoji) {
        this.message = message;
        this.user = user;
        this.emoji = emoji;
    }

    public UUID getMessage() {
        return message;
    }

    public UUID getUser() {
        return user;
    }

    public String getEmoji() {
        return emoji;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MessageReactionId other)) {
            return false;
        }
        return Objects.equals(message, other.message)
                && Objects.equals(user, other.user)
                && Objects.equals(emoji, other.emoji);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, user, emoji);
    }
}
