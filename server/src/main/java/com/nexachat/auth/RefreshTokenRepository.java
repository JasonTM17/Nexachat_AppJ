package com.nexachat.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup by SHA-256 hex of the presented opaque token. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Scalar owner discovery avoids keeping an unlocked entity stale in the persistence context. */
    @Query("SELECT t.user.id FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Same lookup under {@code SELECT … FOR UPDATE}: rotation must serialize on
     * the token row, otherwise two concurrent rotates both read revoked_at=NULL
     * and mint two successor chains — reuse detection would never fire.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.id = :id AND t.user.id = :userId")
    Optional<RefreshToken> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.user.id = :userId ORDER BY t.id")
    List<RefreshToken> findAllByUserIdForUpdate(@Param("userId") UUID userId);

    /** Family revocation: kill every live session of the user in one UPDATE. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt "
            + "WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);

    /** Revoke every live session except the token the caller wants to keep. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt "
            + "WHERE t.user.id = :userId AND t.revokedAt IS NULL AND t.tokenHash <> :keepTokenHash")
    int revokeAllActiveByUserIdExceptTokenHash(@Param("userId") UUID userId,
                                               @Param("keepTokenHash") String keepTokenHash,
                                               @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.replayCiphertext = null, t.replayNonce = null, "
            + "t.replayInvalidated = true WHERE t.user.id = :userId AND t.replayKeyDigest IS NOT NULL")
    int invalidateReplayPayloadsByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            WITH candidates AS (
                SELECT id
                FROM refresh_tokens
                WHERE replay_ciphertext IS NOT NULL AND replay_until <= :now
                ORDER BY replay_until, id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE refresh_tokens token
            SET replay_ciphertext = NULL, replay_nonce = NULL, replay_invalidated = TRUE
            FROM candidates
            WHERE token.id = candidates.id
            """, nativeQuery = true)
    int scrubExpiredReplayPayloads(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
