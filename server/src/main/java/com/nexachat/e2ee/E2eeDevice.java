package com.nexachat.e2ee;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One device identity key (X25519, 32 bytes) belonging to a signed-in user.
 * The id is client-minted per installation; revocation is a soft flag so a
 * stale {@code deviceId} in an envelope can be diagnosed instead of
 * resurrected.
 */
@Entity
@Table(name = "e2ee_devices")
public class E2eeDevice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected E2eeDevice() {
        // JPA only
    }

    public static E2eeDevice register(UUID id, User user, byte[] publicKey) {
        E2eeDevice device = new E2eeDevice();
        device.id = id;
        device.user = user;
        device.publicKey = publicKey;
        return device;
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

    public byte[] getPublicKey() {
        return publicKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }
}
