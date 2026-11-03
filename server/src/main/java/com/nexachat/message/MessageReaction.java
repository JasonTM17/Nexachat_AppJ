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
 * Maps {@code message_reactions} (V3__interactions.sql): one emoji reaction
 * keyed by {@code (message_id, user_id, emoji)} — a user can place several
 * DISTINCT emojis on one message but never the same emoji twice.
 *
 * <p>Like {@link Message}, this entity is a READ model only: writes go through
 * {@link MessageReactionRepository}'s native statements so the toggle is
 * race-safe at the DB level ({@code INSERT ... ON CONFLICT DO NOTHING} plus a
 * plain {@code DELETE}) — the composite PK is the idempotency contract, not a
 * check-then-act window in service code.
 */
@Entity
@Table(name = "message_reactions")
@IdClass(MessageReactionId.class)
public class MessageReaction {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** {@code VARCHAR(16)} in DDL — validated ≤ 16 chars before insert. */
    @Id
    @Column(name = "emoji", nullable = false, length = 16)
    private String emoji;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageReaction() {
        // JPA only
    }

    public Message getMessage() {
        return message;
    }

    public User getUser() {
        return user;
    }

    public String getEmoji() {
        return emoji;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
