package com.nexachat.message;

import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.MessageSearchProjection;
import com.nexachat.message.dto.ReplyRefRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.id = :messageId")
    Optional<Message> findByIdForUpdate(@Param("messageId") UUID messageId);

    /** Latest sequence within one conversation; zero for an empty conversation. */
    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM Message m WHERE m.conversation.id = :conversationId")
    long findMaxSeqByConversationId(@Param("conversationId") UUID conversationId);

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
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.sender.id = :senderId AND m.clientMessageId = :clientMessageId
            """)
    Optional<MessageRow> findRowBySenderAndClientMessageId(@Param("senderId") UUID senderId,
                                                           @Param("clientMessageId") UUID clientMessageId);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.id = :messageId
            """)
    Optional<MessageRow> findRowById(@Param("messageId") UUID messageId);

    /**
     * Cursor page (plan §10.3): newest-first via the
     * {@code idx_msg_conv_seq (conversation_id, seq DESC)} index;
     * {@code beforeSeq} is exclusive so pages never overlap. Callers pass a
     * {@code limit + 1} page size to detect {@code hasMore} without a count query.
     */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
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
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.seq > :afterSeq
            ORDER BY m.seq ASC
            """)
    List<MessageRow> findRowsAfter(@Param("conversationId") UUID conversationId,
                                   @Param("afterSeq") long afterSeq,
                                   Pageable pageable);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.seq <= :lastSeq
              AND m.editedAt > :since
              AND m.deletedAt IS NULL
            ORDER BY m.editedAt ASC
            """)
    List<MessageRow> findEditedRowsSince(@Param("conversationId") UUID conversationId,
                                         @Param("lastSeq") long lastSeq,
                                         @Param("since") Instant since);

    @Query("""
            SELECT m.id FROM Message m
            WHERE m.conversation.id = :conversationId
              AND m.seq <= :lastSeq
              AND m.deletedAt > :since
            """)
    List<UUID> findDeletedMessageIdsSince(@Param("conversationId") UUID conversationId,
                                         @Param("lastSeq") long lastSeq,
                                         @Param("since") Instant since);

    /**
     * Conversation-scoped literal substring search, ordered through
     * idx_msg_conv_seq. The {@code f_unaccent} wrapper is the IMMUTABLE form
     * the trigram index is built on — calling the STABLE {@code unaccent}
     * directly would make the index unusable for this predicate.
     */
    @Query(value = """
            SELECT m.id AS id, m.conversation_id AS "conversationId", m.seq AS seq,
                   m.sender_id AS "senderId", u.display_name AS "senderDisplayName",
                   m.type AS type, m.content AS content,
                   m.client_message_id AS "clientMessageId", reply.id AS "replyToId",
                   m.created_at AS "createdAt", m.edited_at AS "editedAt",
                   m.deleted_at AS "deletedAt"
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            LEFT JOIN messages reply ON reply.id = m.reply_to_id
            WHERE m.conversation_id = :conversationId
              AND m.type = 'TEXT'
              AND m.deleted_at IS NULL
              AND (:beforeSeq IS NULL OR m.seq < :beforeSeq)
              AND public.f_unaccent(m.content) ILIKE public.f_unaccent(:pattern) ESCAPE chr(92)
            ORDER BY m.seq DESC
            """, nativeQuery = true)
    List<MessageSearchProjection> searchTextRows(@Param("conversationId") UUID conversationId,
                                                 @Param("pattern") String pattern,
                                                 @Param("beforeSeq") Long beforeSeq,
                                                 Pageable pageable);

    /** Batch projection fetch for inbox {@code lastMessage} hydration — one IN query. */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.id IN :ids
            """)
    List<MessageRow> findRowsByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Batch reply-target fetch for the {@code replyTo} preview (phase-10):
     * one IN query hydrates every {@code replyToId} on a message page — same
     * projection discipline as the {@link MessageRow} queries.
     */
    @Query("""
            SELECT new com.nexachat.message.dto.ReplyRefRow(
                       m.id, u.id, u.displayName, m.type, m.content, m.deletedAt)
            FROM Message m JOIN m.sender u
            WHERE m.id IN :ids
            """)
    List<ReplyRefRow> findReplyRefsByIdIn(@Param("ids") Collection<UUID> ids);
}
