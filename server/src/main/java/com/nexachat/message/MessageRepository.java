package com.nexachat.message;

import com.nexachat.message.dto.MessageRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * THE send path (plan §9.3.2): a single native statement that is race-safe
     * by construction — a concurrent retry blocks on the conflicting row until
     * the winner commits, then inserts nothing.
     *
     * <p>Nullable parameters are CAST so PostgreSQL can type the bind values
     * when they arrive as NULL (an uncast {@code ?} of unknown type is rejected
     * by the server for native queries).
     *
     * @return 1 when the row was inserted, 0 on a dedup hit
     */
    @Modifying
    @Query(value = """
            INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content, reply_to_id)
            VALUES (:conversationId, :senderId, :clientMessageId, :type,
                    CAST(:content AS text), CAST(:replyToId AS uuid))
            ON CONFLICT (sender_id, client_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicateKey(@Param("conversationId") UUID conversationId,
                                   @Param("senderId") UUID senderId,
                                   @Param("clientMessageId") UUID clientMessageId,
                                   @Param("type") String type,
                                   @Param("content") String content,
                                   @Param("replyToId") UUID replyToId);

    /**
     * Projection read-back after insert-or-skip: fetches the stored row plus
     * the sender's display name in one query (no lazy loading).
     */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.sender.id = :senderId AND m.clientMessageId = :clientMessageId
            """)
    Optional<MessageRow> findRowBySenderAndClientMessageId(@Param("senderId") UUID senderId,
                                                           @Param("clientMessageId") UUID clientMessageId);

    /**
     * Cursor page (plan §10.3): newest-first via the
     * {@code idx_msg_conv_seq (conversation_id, seq DESC)} index;
     * {@code beforeSeq} is exclusive so pages never overlap. Callers pass a
     * {@code limit + 1} page size to detect {@code hasMore} without a count query.
     */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND (:beforeSeq IS NULL OR m.seq < :beforeSeq)
            ORDER BY m.seq DESC
            """)
    List<MessageRow> findPageRows(@Param("conversationId") UUID conversationId,
                                  @Param("beforeSeq") Long beforeSeq,
                                  Pageable pageable);

    /**
     * Forward resync page (plan §11.3.5): oldest-first via {@code seq > afterSeq}
     * so a reconnected client can page forward from its local watermark until
     * contiguous. {@code afterSeq} is exclusive.
     */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.seq > :afterSeq
            ORDER BY m.seq ASC
            """)
    List<MessageRow> findRowsAfter(@Param("conversationId") UUID conversationId,
                                   @Param("afterSeq") long afterSeq,
                                   Pageable pageable);

    /** Batch projection fetch for inbox {@code lastMessage} hydration — one IN query. */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.id IN :ids
            """)
    List<MessageRow> findRowsByIdIn(@Param("ids") Collection<UUID> ids);
}
