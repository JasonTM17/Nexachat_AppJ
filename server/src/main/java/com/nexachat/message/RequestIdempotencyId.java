package com.nexachat.message;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link RequestIdempotency}: {@code (user_id, key)} — a key
 * is scoped to the account that minted it, so the same key from two users is
 * two independent idempotency slots.
 */
public class RequestIdempotencyId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID user;
    private String key;

    protected RequestIdempotencyId() {
        // JPA only
    }

    public RequestIdempotencyId(UUID user, String key) {
        this.user = user;
        this.key = key;
    }

    public UUID getUser() {
        return user;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RequestIdempotencyId other)) {
            return false;
        }
        return Objects.equals(user, other.user) && Objects.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, key);
    }
}
