package com.nexachat.message;

import com.nexachat.message.dto.ReactionEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReactionEventRepository extends JpaRepository<ReactionEvent, Long> {

    /**
     * Append one reaction change. {@code created_at} is left to the column
     * default so the event timestamp is the commit-visible insert time.
     *
     * @return 1 when the row was inserted
     */
    @Modifying
    @Query(value = """
            INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action)
            VALUES (:conversationId, :messageId, :userId, :emoji, :action)
            """, nativeQuery = true)
    int insert(@Param("conversationId") UUID conversationId,
               @Param("messageId") UUID messageId,
               @Param("userId") UUID userId,
               @Param("emoji") String emoji,
               @Param("action") String action);

    /**
     * Keyset page of reaction events for one conversation, oldest id first.
     * {@code cursor} is the last event id the client already received (0 when
     * absent); {@code limit} is the page size. The {@code created_at > since}
     * filter keeps the retention window honest while {@code id} guarantees the
     * page can never skip or repeat a row.
     */
    @Query(value = """
            SELECT id, message_id AS "messageId"
            FROM reaction_events
            WHERE conversation_id = :conversationId
              AND created_at > :since
              AND id > COALESCE(CAST(:cursor AS bigint), 0)
            ORDER BY id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<ReactionEventId> findEventIdsSince(@Param("conversationId") UUID conversationId,
                                            @Param("since") Instant since,
                                            @Param("cursor") Long cursor,
                                            @Param("limit") int limit);

    /** Retention purge: deletes events older than {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM ReactionEvent e WHERE e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
