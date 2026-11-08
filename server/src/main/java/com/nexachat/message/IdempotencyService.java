package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional request idempotency for the mutating reaction and pin endpoints.
 *
 * <p>A caller may send an {@code Idempotency-Key} header (a UUID). The first
 * request with a given {@code (user_id, key)} runs the operation and stores a
 * row; a repeat with the same key, endpoint and payload returns the original
 * result without running the operation again, which is what makes an offline
 * outbox replay safe for a toggle. Reusing a key for a different endpoint or
 * payload is a client bug and is rejected with
 * {@link ApiErrorCode#IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Only successful operations are recorded: a failed first attempt stores
 * nothing, so a retry re-runs. Rows expire after a bounded TTL (see
 * {@link RetentionCleanupTask}).
 */
@Service
public class IdempotencyService {

    /** Matches the {@code request_idempotency.key} column width. */
    static final int MAX_KEY_LENGTH = 64;

    private final RequestIdempotencyRepository repository;

    public IdempotencyService(RequestIdempotencyRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs {@code action} unless {@code key} is a replay.
     *
     * @param userId    the authenticated caller
     * @param key       the raw {@code Idempotency-Key} header, or {@code null}
     * @param endpoint  method + path of the operation (the endpoint identity)
     * @param payload   canonical request payload whose change must be rejected
     * @param action    the operation to run on a first attempt
     * @throws ApiException {@code VALIDATION_FAILED} (blank / oversized /
     *                      non-UUID key), {@code IDEMPOTENCY_KEY_REUSED}
     *                      (same key, different endpoint or payload)
     */
    @Transactional
    public void execute(UUID userId, String key, String endpoint, String payload, Runnable action) {
        if (key == null || key.isBlank()) {
            action.run();
            return;
        }
        String cleanKey = validateKey(key);
        String payloadHash = hash(payload);

        Optional<RequestIdempotency> existing = repository.findByUserIdAndKey(userId, cleanKey);
        if (existing.isPresent()) {
            requireSameOperation(existing.get(), endpoint, payloadHash);
            return; // replay: the original result is returned without re-running
        }

        action.run();

        int inserted = repository.insert(userId, cleanKey, endpoint, payloadHash, hash("204 No Content"));
        if (inserted == 0) {
            // A concurrent request won the insert; its row decides the outcome.
            RequestIdempotency winner = repository.findByUserIdAndKey(userId, cleanKey)
                    .orElseThrow(() -> ApiException.of(
                            ApiErrorCode.INTERNAL_ERROR, "Idempotency row disappeared"));
            requireSameOperation(winner, endpoint, payloadHash);
        }
    }

    private void requireSameOperation(RequestIdempotency row, String endpoint, String payloadHash) {
        if (!row.getEndpoint().equals(endpoint) || !row.getPayloadHash().equals(payloadHash)) {
            throw ApiException.of(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different endpoint or payload");
        }
    }

    /** Trims and bounds the key; the format contract is a UUID. */
    private static String validateKey(String raw) {
        String key = raw.strip();
        if (key.length() > MAX_KEY_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID");
        }
        return key;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available", ex);
        }
    }
}
