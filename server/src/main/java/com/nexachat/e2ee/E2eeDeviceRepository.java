package com.nexachat.e2ee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface E2eeDeviceRepository extends JpaRepository<E2eeDevice, UUID> {

    long countByUser_IdAndRevokedAtIsNull(UUID userId);

    /** Every ACTIVE device of each listed user (member device fan-out for a conversation). */
    List<E2eeDevice> findByUser_IdInAndRevokedAtIsNullOrderByUser_IdAscCreatedAtAsc(
            Collection<UUID> userIds);

    @Query("SELECT d FROM E2eeDevice d WHERE d.user.id = :userId AND d.revokedAt IS NULL")
    List<E2eeDevice> findActiveByUserId(@Param("userId") UUID userId);

    /** Ids of every ACTIVE device owned by one of {@code userIds} — the send-side required set. */
    @Query("SELECT d.id FROM E2eeDevice d WHERE d.user.id IN :userIds AND d.revokedAt IS NULL")
    List<UUID> findActiveIdsForMembers(@Param("userIds") Collection<UUID> userIds);

    /** Marks every active device of the user revoked (logout-all, account deletion). */
    @Modifying
    @Query("UPDATE E2eeDevice d SET d.revokedAt = :at"
            + " WHERE d.user.id = :userId AND d.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("at") Instant at);

    Optional<E2eeDevice> findById(UUID id);
}
