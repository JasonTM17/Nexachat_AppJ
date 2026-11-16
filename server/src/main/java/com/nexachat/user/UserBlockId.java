package com.nexachat.user;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link UserBlock}: {@code (blocker_id, blocked_id)}.
 * Field names match the entity's id attributes — the
 * {@code ConversationMemberId} pattern.
 */
public class UserBlockId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID blocker;
    private UUID blocked;

    protected UserBlockId() {
        // JPA only
    }

    public UserBlockId(UUID blocker, UUID blocked) {
        this.blocker = blocker;
        this.blocked = blocked;
    }

    public UUID getBlocker() {
        return blocker;
    }

    public UUID getBlocked() {
        return blocked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserBlockId other)) {
            return false;
        }
        return Objects.equals(blocker, other.blocker)
                && Objects.equals(blocked, other.blocked);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blocker, blocked);
    }
}
