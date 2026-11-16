package com.nexachat.user;

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
 * Maps {@code user_blocks} (V38): a DIRECTED safety edge —
 * {@code (blocker, blocked)} means "blocker refuses contact from blocked".
 * The composite PK makes a repeat block a no-op at the storage layer.
 *
 * <p>Read model only — writes are the native statements in
 * {@link UserBlockRepository} (same discipline as {@code pinned_messages}).
 */
@Entity
@Table(name = "user_blocks")
@IdClass(UserBlockId.class)
public class UserBlock {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserBlock() {
        // JPA only
    }

    public User getBlocker() {
        return blocker;
    }

    public User getBlocked() {
        return blocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
