package com.nexachat.message;

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
 * Maps {@code message_stars} (V38): a PER-USER marker —
 * {@code (user, message)} means "user saved this message for themselves".
 * Unlike {@code pinned_messages} it is not shared with the conversation and
 * fans no event out; the composite PK makes a repeat star a no-op.
 *
 * <p>Read model only — writes are the native statements in
 * {@link MessageStarRepository} (same discipline as {@code user_blocks}).
 */
@Entity
@Table(name = "message_stars")
@IdClass(MessageStarId.class)
public class MessageStar {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageStar() {
        // JPA only
    }

    public User getUser() {
        return user;
    }

    public Message getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
