package com.nexachat.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup by SHA-256 hex of the presented opaque token. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Same lookup under {@code SELECT … FOR UPDATE}: rotation must serialize on
     * the token row, otherwise two concurrent rotates both read revoked_at=NULL
     * and mint two successor chains — reuse detection would never fire.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

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
}
