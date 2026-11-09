package com.nexachat.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RequestIdempotencyRepository
        extends JpaRepository<RequestIdempotency, RequestIdempotencyId> {

    @Query("SELECT r FROM RequestIdempotency r WHERE r.user.id = :userId AND r.key = :key")
    Optional<RequestIdempotency> findByUserIdAndKey(@Param("userId") UUID userId,
                                                    @Param("key") String key);

    /**
     * Race-safe reservation insert: a concurrent conflicting insert waits for
     * this transaction, then returns 0 after the winner commits. The service
     * reserves before invoking the action so same-key mutations run once.
     *
     * @return 1 when this call stored the row, 0 when the key already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO request_idempotency (user_id, key, endpoint, payload_hash, response_hash)
            VALUES (:userId, :key, :endpoint, :payloadHash, :responseHash)
            ON CONFLICT (user_id, key) DO NOTHING
            """, nativeQuery = true)
    int insert(@Param("userId") UUID userId,
               @Param("key") String key,
               @Param("endpoint") String endpoint,
               @Param("payloadHash") String payloadHash,
               @Param("responseHash") String responseHash);
}
