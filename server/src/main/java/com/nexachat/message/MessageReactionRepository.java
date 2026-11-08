package com.nexachat.message;

import com.nexachat.message.dto.ReactionRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, MessageReactionId> {

    /**
     * The add half of the reaction toggle (plan §10.2): a single native
     * statement that is race-safe by construction — two concurrent adds of
     * the same (message, user, emoji) simply conflict and the loser inserts
     * nothing.
     *
     * @return 1 when the row was inserted, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO message_reactions (message_id, user_id, emoji)
            VALUES (:messageId, :userId, :emoji)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("messageId") UUID messageId,
                               @Param("userId") UUID userId,
                               @Param("emoji") String emoji);

    /**
     * The remove half of the toggle: deletes exactly the caller's
     * (message, user, emoji) row.
     *
     * @return 1 when a row was removed, 0 when the user had no such reaction
     */
    @Modifying
    @Query("""
            DELETE FROM MessageReaction r
            WHERE r.message.id = :messageId AND r.user.id = :userId AND r.emoji = :emoji
            """)
    int deleteOne(@Param("messageId") UUID messageId,
                  @Param("userId") UUID userId,
                  @Param("emoji") String emoji);

    @Modifying
    @Query("DELETE FROM MessageReaction r WHERE r.message.id = :messageId")
    int deleteByMessageId(@Param("messageId") UUID messageId);

    /**
     * Batch read for aggregation — one IN query feeds both the message-page
     * {@code reactions[]} summaries (grouped per message) and the
     * {@code REACTION_UPDATED} broadcast payload (grouped per emoji). Direct
     * projection, no entity graph (plan §9.3.7).
     */
    @Query("""
            SELECT new com.nexachat.message.dto.ReactionRow(
                       r.message.id, r.user.id, r.emoji)
            FROM MessageReaction r
            WHERE r.message.id IN :messageIds
            """)
    List<ReactionRow> findRowsByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
