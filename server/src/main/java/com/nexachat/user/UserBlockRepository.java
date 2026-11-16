package com.nexachat.user;

import com.nexachat.user.dto.BlockedUserView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

    /**
     * Race-safe block write: re-blocking an already-blocked pair conflicts on
     * the composite PK and inserts nothing — the same
     * {@code ON CONFLICT DO NOTHING} discipline as the pin/send paths.
     *
     * @return 1 when the block was created, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_blocks (blocker_id, blocked_id)
            VALUES (:blockerId, :blockedId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("blockerId") UUID blockerId,
                               @Param("blockedId") UUID blockedId);

    /**
     * Unblock: removes exactly the (blocker, blocked) edge.
     *
     * @return 1 when a block was removed, 0 when the pair was not blocked
     */
    @Modifying
    @Query("""
            DELETE FROM UserBlock b
            WHERE b.blocker.id = :blockerId AND b.blocked.id = :blockedId
            """)
    int deleteOne(@Param("blockerId") UUID blockerId,
                  @Param("blockedId") UUID blockedId);

    /** {@code true} when {@code blockerId} currently blocks {@code blockedId}. */
    boolean existsByBlocker_IdAndBlocked_Id(UUID blockerId, UUID blockedId);

    /**
     * {@code true} when a block edge exists in EITHER direction between the
     * two users. Used by symmetric contact surfaces (typing, read/delivery
     * receipts, calls) where seeing each other's activity must stop for both
     * sides.
     */
    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
            FROM UserBlock b
            WHERE (b.blocker.id = :a AND b.blocked.id = :bId)
               OR (b.blocker.id = :bId AND b.blocked.id = :a)
            """)
    boolean existsBetweenEitherDirection(@Param("a") UUID a, @Param("bId") UUID bId);

    /**
     * Members of {@code candidateIds} that {@code blockedId} currently blocks:
     * "users I block", for the blocked-list screen and avatar scrubbing.
     */
    @Query("""
            SELECT b.blocked.id FROM UserBlock b
            WHERE b.blocker.id = :blockerId AND b.blocked.id IN :candidateIds
            """)
    List<UUID> findBlockedIdsAmong(@Param("blockerId") UUID blockerId,
                                   @Param("candidateIds") Collection<UUID> candidateIds);

    /**
     * Members of {@code candidateIds} that currently block {@code userId}:
     * "users who blocked me", for push/presence/group-add recipient filters.
     */
    @Query("""
            SELECT b.blocker.id FROM UserBlock b
            WHERE b.blocked.id = :userId AND b.blocker.id IN :candidateIds
            """)
    List<UUID> findBlockerIdsBlocking(@Param("userId") UUID userId,
                                      @Param("candidateIds") Collection<UUID> candidateIds);

    /**
     * Members of {@code candidateIds} related to {@code userId} by a block in
     * EITHER direction — the privacy-scrub set for presence/avatar surfaces.
     */
    @Query("""
            SELECT CASE WHEN b.blocker.id = :userId THEN b.blocked.id ELSE b.blocker.id END
            FROM UserBlock b
            WHERE (b.blocker.id = :userId AND b.blocked.id IN :candidateIds)
               OR (b.blocked.id = :userId AND b.blocker.id IN :candidateIds)
            """)
    List<UUID> findRelatedIdsEitherDirection(@Param("userId") UUID userId,
                                             @Param("candidateIds") Collection<UUID> candidateIds);

    /** Blocked-list page: newest block first, stable id tie-breaker for the keyset cursor. */
    @Query("""
            SELECT new com.nexachat.user.dto.BlockedUserView(
                       u.id, u.displayName, u.phone, u.avatarUrl, b.createdAt)
            FROM UserBlock b JOIN b.blocked u
            WHERE b.blocker.id = :userId
            ORDER BY b.createdAt DESC, b.blocked.id ASC
            """)
    List<BlockedUserView> findBlockedPageFirst(@Param("userId") UUID userId,
                                               Pageable pageable);

    @Query("""
            SELECT new com.nexachat.user.dto.BlockedUserView(
                       u.id, u.displayName, u.phone, u.avatarUrl, b.createdAt)
            FROM UserBlock b JOIN b.blocked u
            WHERE b.blocker.id = :userId
              AND (b.createdAt < :cursorCreatedAt
                   OR (b.createdAt = :cursorCreatedAt AND b.blocked.id > :cursorBlockedId))
            ORDER BY b.createdAt DESC, b.blocked.id ASC
            """)
    List<BlockedUserView> findBlockedPageAfter(@Param("userId") UUID userId,
                                               @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                               @Param("cursorBlockedId") UUID cursorBlockedId,
                                               Pageable pageable);
}
