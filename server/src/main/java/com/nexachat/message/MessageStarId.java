package com.nexachat.message;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link MessageStar}: {@code (user_id, message_id)}.
 * Field names match the entity's id attributes — the
 * {@code UserBlockId} pattern.
 */
public class MessageStarId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID user;
    private UUID message;

    protected MessageStarId() {
        // JPA only
    }

    public MessageStarId(UUID user, UUID message) {
        this.user = user;
        this.message = message;
    }

    public UUID getUser() {
        return user;
    }

    public UUID getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MessageStarId other)) {
            return false;
        }
        return Objects.equals(user, other.user)
                && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, message);
    }
}
