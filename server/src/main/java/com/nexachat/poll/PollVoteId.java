package com.nexachat.poll;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link PollVote}: {@code (poll_id, option_id, user_id)}.
 * Same {@code @IdClass}-on-relationship style as
 * {@code ConversationMemberId}; field names match the entity id attributes.
 */
public class PollVoteId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID poll;
    private UUID option;
    private UUID user;

    protected PollVoteId() {
        // JPA only
    }

    public PollVoteId(UUID poll, UUID option, UUID user) {
        this.poll = poll;
        this.option = option;
        this.user = user;
    }

    public UUID getPoll() {
        return poll;
    }

    public UUID getOption() {
        return option;
    }

    public UUID getUser() {
        return user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PollVoteId other)) {
            return false;
        }
        return Objects.equals(poll, other.poll)
                && Objects.equals(option, other.option)
                && Objects.equals(user, other.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(poll, option, user);
    }
}
