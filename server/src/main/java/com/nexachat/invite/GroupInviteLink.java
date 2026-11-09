package com.nexachat.invite;

import com.nexachat.conversation.Conversation;
import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps {@code group_invite_links} (V20__group_invite_links.sql).
 *
 * <p>Stores hashed tokens (SHA-256) to ensure raw tokens are never persisted in plaintext.
 * An active invite link can optionally have an expiration date (up to 30 days) and
 * maximum usage count. When rotated or revoked, {@code revokedAt} and {@code revokedBy}
 * are populated.
 */
@Entity
@Table(name = "group_invite_links")
public class GroupInviteLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GroupInviteLink() {
        // JPA only
    }

    public GroupInviteLink(Conversation conversation,
                           User createdBy,
                           String tokenHash,
                           boolean requiresApproval,
                           Instant expiresAt,
                           Integer maxUses) {
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.requiresApproval = requiresApproval;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.useCount = 0;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public boolean isUsedUp() {
        return maxUses != null && useCount >= maxUses;
    }

    public boolean isValid(Instant now) {
        return !isRevoked() && !isExpired(now) && !isUsedUp();
    }

    public void revoke(User user, Instant now) {
        this.revokedAt = now != null ? now : Instant.now();
        this.revokedBy = user;
        this.updatedAt = this.revokedAt;
    }

    public void incrementUseCount() {
        this.useCount++;
        this.updatedAt = Instant.now();
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public int getUseCount() {
        return useCount;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public User getRevokedBy() {
        return revokedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
