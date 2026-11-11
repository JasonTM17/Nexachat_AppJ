package com.nexachat.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code users} (V1__init.sql). {@code phone} is the unique identity in
 * E.164 form (+84912345678); {@code password_hash} holds a bcrypt(12) digest.
 *
 * <p>Never returned to clients — controllers must map to
 * {@link com.nexachat.user.dto.UserResponse} (NFR-5).
 */
@Entity
@Table(name = "users")
@DynamicUpdate
public class User {

    /** App-side UUID; the DB {@code DEFAULT gen_random_uuid()} stays as a safety net. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone", length = 16)
    private String phone;

    /** bcrypt output is always exactly 60 chars; the column is CHAR(60) not VARCHAR. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "show_presence", nullable = false)
    private boolean showPresence = true;

    /**
     * Read-receipt privacy switch (V16). {@code true} (the default) broadcasts
     * this user's {@code MESSAGE_READ} receipts and lets the user receive
     * other members' receipts; {@code false} is the reciprocal Zalo-style
     * "hidden seen" state. Unread counts are never affected.
     */
    @Column(name = "show_seen", nullable = false)
    private boolean showSeen = true;

    @Column(name = "ai_summary_consent", nullable = false)
    private boolean aiSummaryConsent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Access tokens issued before this instant are rejected. {@code null} means
     * no cutoff. Stamped by logout, logout-all and password change.
     */
    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    /** Non-null once the owner has deleted the account through DELETE /users/me. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    protected User() {
        // JPA only
    }

    public static User create(String phone, String passwordHash, String displayName) {
        User user = new User();
        user.phone = phone;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        return user;
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

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }

    /**
     * Rejects every access token issued at or before {@code cutoff}. Called by
     * logout, logout-all and password change.
     */
    public void invalidateAccessTokens(Instant cutoff) {
        this.tokensValidAfter = cutoff;
    }

    public boolean isShowPresence() {
        return showPresence;
    }

    public boolean isShowSeen() {
        return showSeen;
    }

    public boolean isAiSummaryConsent() {
        return aiSummaryConsent;
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void changeAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changeShowPresence(boolean showPresence) {
        this.showPresence = showPresence;
    }

    public void changeShowSeen(boolean showSeen) {
        this.showSeen = showSeen;
    }

    public void changeAiSummaryConsent(boolean aiSummaryConsent) {
        this.aiSummaryConsent = aiSummaryConsent;
    }

    public void recordLastSeen(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getEmail() {
        return email;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public boolean hasVerifiedEmail() {
        return email != null && emailVerifiedAt != null;
    }

    public void changeEmail(String email) {
        this.email = email;
        this.emailVerifiedAt = null;
    }

    public void verifyEmail(Instant verifiedAt) {
        this.emailVerifiedAt = verifiedAt;
    }

    public void removeEmail() {
        this.email = null;
        this.emailVerifiedAt = null;
    }

    /**
     * Marks the account deleted: frees {@link #phone}, scrubs profile fields and
     * stamps {@link #deletedAt}. Called only from account deletion.
     */
    public void markDeleted(Instant deletedAt) {
        this.deletedAt = deletedAt;
        this.phone = null;
        this.displayName = DeletedAccountPolicy.DISPLAY_NAME;
        this.avatarUrl = null;
        this.showPresence = false;
        this.showSeen = false;
        this.aiSummaryConsent = false;
        this.lastSeenAt = null;
        this.email = null;
        this.emailVerifiedAt = null;
        this.passwordHash = DeletedAccountPolicy.TOMBSTONE_PASSWORD_HASH;
    }
}
