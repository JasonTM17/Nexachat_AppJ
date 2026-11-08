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
 * Maps {@code request_idempotency} (V10): the cached outcome of one mutating
 * request, keyed by {@code (user_id, key)}. Only successful requests are
 * stored — a failed first attempt stores nothing, so a retry re-runs.
 *
 * <p>The composite key is the isolation boundary: the same key from a
 * different user is a different slot, and the same key on a different
 * endpoint or payload is rejected as a client bug rather than silently
 * returning the wrong result.
 */
@Entity
@Table(name = "request_idempotency")
@IdClass(RequestIdempotencyId.class)
public class RequestIdempotency {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "endpoint", nullable = false, length = 255)
    private String endpoint;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "response_hash", nullable = false, length = 64)
    private String responseHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequestIdempotency() {
        // JPA only
    }

    public User getUser() {
        return user;
    }

    public String getKey() {
        return key;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getResponseHash() {
        return responseHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
