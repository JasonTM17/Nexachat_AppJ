package com.nexachat.message;

import com.nexachat.message.dto.StarRef;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageStarRepository extends JpaRepository<MessageStar, MessageStarId> {

    /**
     * Race-safe star write: a repeat star conflicts on the composite PK and
     * inserts nothing — the same {@code ON CONFLICT DO NOTHING} discipline
     * as the block/pin paths.
     *
     * @return 1 when the star was created, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO message_stars (user_id, message_id)
            VALUES (:userId, :messageId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("userId") UUID userId,
                               @Param("messageId") UUID messageId);

    /**
     * Unstar: removes exactly the (user, message) edge.
     *
     * @return 1 when a star was removed, 0 when it was not starred
     */
    @Modifying
    @Query("""
            DELETE FROM MessageStar s
            WHERE s.user.id = :userId AND s.message.id = :messageId
            """)
    int deleteOne(@Param("userId") UUID userId,
                  @Param("messageId") UUID messageId);

    /**
     * Starred-page refs, newest star first with a message-id tie-breaker for
     * the keyset cursor. The page step intentionally fetches refs only; the
     * caller re-fetches viewer-scoped rows so suppression and membership
     * predicates stay in one place ({@link MessageRepository}).
     */
    @Query("""
            SELECT new com.nexachat.message.dto.StarRef(s.message.id, s.createdAt)
            FROM MessageStar s
            WHERE s.user.id = :userId
            ORDER BY s.createdAt DESC, s.message.id ASC
            """)
    List<StarRef> findStarredRefsFirst(@Param("userId") UUID userId,
                                       Pageable pageable);

    @Query("""
            SELECT new com.nexachat.message.dto.StarRef(s.message.id, s.createdAt)
            FROM MessageStar s
            WHERE s.user.id = :userId
              AND (s.createdAt < :cursorCreatedAt
                   OR (s.createdAt = :cursorCreatedAt AND s.message.id > :cursorMessageId))
            ORDER BY s.createdAt DESC, s.message.id ASC
            """)
    List<StarRef> findStarredRefsAfter(@Param("userId") UUID userId,
                                       @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                       @Param("cursorMessageId") UUID cursorMessageId,
                                       Pageable pageable);
}
