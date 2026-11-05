package com.nexachat.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
public class User {

    /** App-side UUID; the DB {@code DEFAULT gen_random_uuid()} stays as a safety net. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone", nullable = false, length = 16, unique = true)
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

    @Column(name = "ai_summary_consent", nullable = false)
    private boolean aiSummaryConsent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public boolean isShowPresence() {
        return showPresence;
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

    public void changeAiSummaryConsent(boolean aiSummaryConsent) {
        this.aiSummaryConsent = aiSummaryConsent;
    }

    public void recordLastSeen(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
