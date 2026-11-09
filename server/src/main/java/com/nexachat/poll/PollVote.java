package com.nexachat.poll;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read model of {@code poll_votes} (V15__group_polls.sql). The composite key
 * {@code (poll, option, user)} is the duplicate-vote guard; writes go through
 * {@link PollVoteRepository} statements under the poll row lock so a
 * single-choice replacement stays one atomic delete-plus-insert.
 */
@Entity
@Table(name = "poll_votes")
@IdClass(PollVoteId.class)
public class PollVote {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private PollOption option;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PollVote() {
        // JPA only
    }

    public Poll getPoll() {
        return poll;
    }

    public PollOption getOption() {
        return option;
    }

    public User getUser() {
        return user;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
