package com.nexachat.auth;

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
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code refresh_tokens} (V1__init.sql).
 *
 * <p>Only the SHA-256 hex of the opaque token is stored ({@code token_hash}) — a
 * leaked DB row cannot be replayed (§13 refresh-replay row). {@code replaced_by}
 * links each rotated token to its successor, forming the audit chain
 * {@code RT1 → RT2 → …}; {@code revoked_at != null} marks a dead token, and any
 * later presentation of it is treated as reuse.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** LAZY: loading a token must not drag the user row along (plan §9.3.7). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex is always exactly 64 chars; the column is CHAR(64) not VARCHAR. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "device_label", length = 80)
    private String deviceLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
        // JPA only
    }

    public static RefreshToken issue(User user, String tokenHash, String deviceLabel, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.deviceLabel = deviceLabel;
        token.expiresAt = expiresAt;
        return token;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant instant) {
        return !expiresAt.isAfter(instant);
    }

    public void revoke(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public void markReplacedBy(UUID replacementId) {
        this.replacedBy = replacementId;
    }
}
