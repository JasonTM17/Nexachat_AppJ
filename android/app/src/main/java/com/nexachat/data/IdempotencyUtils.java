package com.nexachat.data;

import androidx.annotation.NonNull;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * RFC 4122 UUIDv5 generator and deterministic action idempotency key utilities.
 * Eliminates toggle inversion by requiring explicit ADD or REMOVE actions.
 */
public final class IdempotencyUtils {
    /** RFC 4122 URL namespace used as root namespace for NexaChat deterministic keys. */
    public static final UUID NAMESPACE_NEXACHAT =
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    public static final String ACTION_ADD = "ADD";
    public static final String ACTION_REMOVE = "REMOVE";

    private IdempotencyUtils() {}

    /**
     * Generates a deterministic UUID version 5 per RFC 4122 §4.3 (SHA-1 hashing).
     */
    @NonNull
    public static UUID uuidV5(@NonNull UUID namespace, @NonNull String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            ByteBuffer nsBuf = ByteBuffer.allocate(16);
            nsBuf.putLong(namespace.getMostSignificantBits());
            nsBuf.putLong(namespace.getLeastSignificantBits());
            md.update(nsBuf.array());
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();

            // Set version to 5 (0101 in bits 12-15 of time_hi_and_version)
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            // Set variant to RFC 4122 (10 in bits 6-7 of clock_seq_hi_and_reserved)
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);

            ByteBuffer resultBuf = ByteBuffer.wrap(hash);
            long msb = resultBuf.getLong();
            long lsb = resultBuf.getLong();
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 algorithm must be supported by Java JVM", e);
        }
    }

    /**
     * Computes deterministic idempotency key for reaction actions:
     * UUIDv5(userId + ":" + messageId + ":" + emoji + ":" + action)
     * with explicit ADD or REMOVE.
     */
    @NonNull
    public static String reactionIdempotencyKey(
            @NonNull String userId,
            @NonNull String messageId,
            @NonNull String emoji,
            @NonNull String action) {
        String seed = userId + ":" + messageId + ":" + emoji + ":" + action;
        return uuidV5(NAMESPACE_NEXACHAT, seed).toString();
    }

    /**
     * Computes deterministic idempotency key for reaction actions with boolean isAdd.
     */
    @NonNull
    public static String reactionIdempotencyKey(
            @NonNull String userId,
            @NonNull String messageId,
            @NonNull String emoji,
            boolean isAdd) {
        return reactionIdempotencyKey(userId, messageId, emoji, isAdd ? ACTION_ADD : ACTION_REMOVE);
    }

    /**
     * Computes deterministic idempotency key for reaction toggle compatibility:
     * UUIDv5(userId + ":" + messageId + ":" + emoji).
     */
    @NonNull
    public static String reactionIdempotencyKey(
            @NonNull String userId,
            @NonNull String messageId,
            @NonNull String emoji) {
        String seed = userId + ":" + messageId + ":" + emoji;
        return uuidV5(NAMESPACE_NEXACHAT, seed).toString();
    }

    /**
     * Computes deterministic idempotency key for pin/unpin actions:
     * UUIDv5(userId + ":" + conversationId + ":" + messageId + ":" + (pinned ? "PIN" : "UNPIN")).
     */
    @NonNull
    public static String pinIdempotencyKey(
            @NonNull String userId,
            @NonNull String conversationId,
            @NonNull String messageId,
            boolean pinned) {
        String seed = userId + ":" + conversationId + ":" + messageId + ":" + (pinned ? "PIN" : "UNPIN");
        return uuidV5(NAMESPACE_NEXACHAT, seed).toString();
    }
}
