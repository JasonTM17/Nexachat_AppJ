package com.nexachat.notification;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps {@code user_device_tokens} (V7__p0_receipts_sync_fcm.sql):
 * Device registration tokens for FCM/APNs push notifications.
 */
@Entity
@Table(name = "user_device_tokens")
public class UserDeviceToken {

    public enum Platform {
        ANDROID,
        IOS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private Platform platform = Platform.ANDROID;

    @Column(name = "device_label", length = 100)
    private String deviceLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected UserDeviceToken() {
        // JPA only
    }

    public static UserDeviceToken create(User user, String token, Platform platform, String deviceLabel) {
        UserDeviceToken entity = new UserDeviceToken();
        entity.user = Objects.requireNonNull(user, "user");
        entity.token = Objects.requireNonNull(token, "token").strip();
        entity.platform = platform != null ? platform : Platform.ANDROID;
        entity.deviceLabel = deviceLabel;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        entity.lastUsedAt = entity.createdAt;
        return entity;
    }

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    public void updateUsage(String deviceLabel, Platform platform) {
        this.updatedAt = Instant.now();
        this.lastUsedAt = this.updatedAt;
        if (deviceLabel != null && !deviceLabel.isBlank()) {
            this.deviceLabel = deviceLabel;
        }
        if (platform != null) {
            this.platform = platform;
        }
    }

    public void touch() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getToken() {
        return token;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
