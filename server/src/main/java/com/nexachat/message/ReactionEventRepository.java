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
     * Serializes reaction-event inserts and delta reads for one conversation.
     * The transaction-scoped key ensures event ids are allocated only after
     * the preceding holder commits or rolls back.
     */
    @Query(value = """
            WITH lock_acquired AS MATERIALIZED (
                SELECT pg_advisory_xact_lock(hashtextextended(CAST(:conversationId AS text), 0))
            )
            SELECT 1 FROM lock_acquired
            """, nativeQuery = true)
    Integer lockConversationEvents(@Param("conversationId") UUID conversationId);

    /**
     * Append one reaction change. {@code clock_timestamp()} is evaluated
     * after the conversation lock is acquired, rather than at transaction start.
     *
     * @return 1 when the row was inserted
     */
    @Modifying
    @Query(value = """
            INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action, created_at)
            VALUES (:conversationId, :messageId, :userId, :emoji, :action, clock_timestamp())
            """, nativeQuery = true)
    int insert(@Param("conversationId") UUID conversationId,
               @Param("messageId") UUID messageId,
               @Param("userId") UUID userId,
               @Param("emoji") String emoji,
               @Param("action") String action);

    /**
     * Keyset page of reaction events for one conversation, oldest id first.
     * {@code cursor} is the last event id the client already received (0 when
     * absent); {@code limit} is the page size. The first page filters by
     * {@code created_at > since}; continuation pages use only the id cursor.
     * Writers and readers hold the same transaction lock so ids are allocated
     * in commit order and a late commit cannot fall behind a cursor.
     */
    @Query(value = """
            SELECT id, message_id AS "messageId"
            FROM reaction_events
            WHERE conversation_id = :conversationId
              AND (CAST(:cursor AS bigint) IS NOT NULL OR created_at > :since)
              AND id > COALESCE(CAST(:cursor AS bigint), 0)
            ORDER BY id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<ReactionEventId> findEventIdsSince(@Param("conversationId") UUID conversationId,
                                            @Param("since") Instant since,
                                            @Param("cursor") Long cursor,
                                            @Param("limit") int limit);
}
