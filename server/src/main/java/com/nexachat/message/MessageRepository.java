package com.nexachat.message;

import com.nexachat.message.dto.MessageDeletionChange;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.MessageSearchProjection;
import com.nexachat.message.dto.ReplyRefRow;
import com.nexachat.message.dto.SyncFence;
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

    /**
     * Read-only conversation discovery for lock ordering: a caller that must
     * lock the conversation before the message uses this projection first, so
     * the non-locking probe never leaves a managed {@link Message} behind.
     */
    @Query("SELECT m.conversation.id FROM Message m WHERE m.id = :messageId")
    Optional<UUID> findConversationIdById(@Param("messageId") UUID messageId);

    /** Serialize only identical sender/client keys before the conversation lock. */
    @Query(value = """
            WITH lock_acquired AS MATERIALIZED (
                SELECT pg_advisory_xact_lock(hashtextextended(
                    CAST(:senderId AS text) || ':' || CAST(:clientMessageId AS text), 0))
            )
            SELECT 1 FROM lock_acquired
            """, nativeQuery = true)
    Integer lockIdempotencyKey(@Param("senderId") UUID senderId,
                               @Param("clientMessageId") UUID clientMessageId);

    /** Latest sequence within one conversation; zero for an empty conversation. */
    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM Message m WHERE m.conversation.id = :conversationId")
    long findMaxSeqByConversationId(@Param("conversationId") UUID conversationId);

    /**
     * Native send insert. The caller holds the idempotency advisory key and
     * conversation row lock; the unique constraint remains the final guard
     * against writers that do not use this path.
     *
     * <p>Nullable parameters are CAST so PostgreSQL can type the bind values
     * when they arrive as NULL (an uncast {@code ?} of unknown type is rejected
     * by the server for native queries).
     *
     * @return 1 when the row was inserted, 0 on a dedup hit
     */
    @Modifying
    @Query(value = """
            INSERT INTO messages
                (conversation_id, sender_id, client_message_id, type, content, reply_to_id, change_version, created_at)
            SELECT :conversationId, :senderId, :clientMessageId, :type,
                   CAST(:content AS text), CAST(:replyToId AS uuid), c.change_version, clock_timestamp()
            FROM conversations c WHERE c.id = :conversationId
            ON CONFLICT (sender_id, client_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicateKey(@Param("conversationId") UUID conversationId,
                                   @Param("senderId") UUID senderId,
                                   @Param("clientMessageId") UUID clientMessageId,
                                   @Param("type") String type,
                                   @Param("content") String content,
                                   @Param("replyToId") UUID replyToId);

    @Modifying
    @Query(value = """
            INSERT INTO messages
                (conversation_id, sender_id, client_message_id, type, content, reply_to_id, mention_all,
                 change_version, created_at)
            SELECT :conversationId, :senderId, :clientMessageId, :type,
                   CAST(:content AS text), CAST(:replyToId AS uuid), :mentionAll, c.change_version, clock_timestamp()
            FROM conversations c WHERE c.id = :conversationId
            ON CONFLICT (sender_id, client_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicateKeyWithMentionAll(@Param("conversationId") UUID conversationId,
                                                 @Param("senderId") UUID senderId,
                                                 @Param("clientMessageId") UUID clientMessageId,
                                                 @Param("type") String type,
                                                 @Param("content") String content,
                                                 @Param("replyToId") UUID replyToId,
                                                 @Param("mentionAll") boolean mentionAll);

    /**
     * Projection read-back after insert-or-skip: fetches the stored row plus
     * the sender's display name in one query (no lazy loading).
     */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.sender.id = :senderId AND m.clientMessageId = :clientMessageId
            """)
    Optional<MessageRow> findRowBySenderAndClientMessageId(@Param("senderId") UUID senderId,
                                                           @Param("clientMessageId") UUID clientMessageId);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
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
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
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
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.seq > :afterSeq
            ORDER BY m.seq ASC
            """)
    List<MessageRow> findRowsAfter(@Param("conversationId") UUID conversationId,
                                   @Param("afterSeq") long afterSeq,
                                   Pageable pageable);

    /** Sync cursors are positions, never authority to read a previous membership epoch. */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId AND m.seq > :afterSeq
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            ORDER BY m.seq ASC
            """)
    List<MessageRow> findSyncRowsAfter(@Param("userId") UUID userId,
                                       @Param("conversationId") UUID conversationId,
                                       @Param("afterSeq") long afterSeq, Pageable pageable);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.changeVersion > :changeCursor AND m.changeVersion <= :changeUpperBound
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            ORDER BY m.changeVersion ASC, m.id ASC
            """)
    List<MessageRow> findSyncChangesBetween(@Param("userId") UUID userId,
                                            @Param("conversationId") UUID conversationId,
                                            @Param("changeCursor") long changeCursor,
                                            @Param("changeUpperBound") long changeUpperBound, Pageable pageable);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId AND m.seq <= :lastSeq
              AND m.editedAt > :since AND m.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            ORDER BY m.editedAt ASC, m.id ASC
            """)
    List<MessageRow> findSyncEditedRowsSince(@Param("userId") UUID userId,
                                             @Param("conversationId") UUID conversationId,
                                             @Param("lastSeq") long lastSeq, @Param("since") Instant since);

    @Query("""
            SELECT m.id FROM Message m
            WHERE m.conversation.id = :conversationId AND m.seq <= :lastSeq AND m.deletedAt > :since
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            """)
    List<UUID> findSyncDeletedMessageIdsSince(@Param("userId") UUID userId,
                                              @Param("conversationId") UUID conversationId,
                                              @Param("lastSeq") long lastSeq, @Param("since") Instant since);

    /** Capture H/G and prove current membership in one plain scalar projection. */
    @Query("""
            SELECT new com.nexachat.message.dto.SyncFence(c.changeVersion, c.syncGeneration)
            FROM Conversation c, ConversationMember member
            WHERE c.id = :conversationId
              AND member.conversation.id = c.id
              AND member.user.id = :userId
            """)
    Optional<SyncFence> findSyncFence(@Param("userId") UUID userId,
                                      @Param("conversationId") UUID conversationId);

    /** Bounded current-state page through an immutable H captured by findSyncFence. */
    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
            FROM Message m JOIN m.sender u LEFT JOIN m.replyTo r
            WHERE m.conversation.id = :conversationId
              AND m.changeVersion > :changeCursor
              AND m.changeVersion <= :changeUpperBound
            ORDER BY m.changeVersion ASC, m.id ASC
            """)
    List<MessageRow> findChangesBetween(@Param("conversationId") UUID conversationId,
                                        @Param("changeCursor") long changeCursor,
                                        @Param("changeUpperBound") long changeUpperBound,
                                        Pageable pageable);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageRow(
                       m.id, m.conversation.id, m.seq, u.id, u.displayName, m.type,
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
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

    @Query("""
            SELECT new com.nexachat.message.dto.MessageDeletionChange(m.id, m.deletedAt)
            FROM Message m
            WHERE m.conversation.id = :conversationId
              AND m.seq <= :lastSeq
              AND m.deletedAt > :since
            ORDER BY m.deletedAt ASC, m.id ASC
            """)
    List<MessageDeletionChange> findDeletedChangesSince(@Param("conversationId") UUID conversationId,
                                                         @Param("lastSeq") long lastSeq,
                                                         @Param("since") Instant since);

    @Query("""
            SELECT new com.nexachat.message.dto.MessageDeletionChange(m.id, m.deletedAt)
            FROM Message m
            WHERE m.conversation.id = :conversationId
              AND m.seq <= :lastSeq
              AND m.deletedAt > :since
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            ORDER BY m.deletedAt ASC, m.id ASC
            """)
    List<MessageDeletionChange> findSyncDeletedChangesSince(@Param("userId") UUID userId, @Param("conversationId") UUID conversationId,
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
                   m.deleted_at AS "deletedAt", m.mention_all AS "mentionAll",
                   m.change_version AS "changeVersion", c.sync_generation AS generation
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
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
                       m.content, m.clientMessageId, r.id, m.createdAt, m.editedAt, m.deletedAt,
                       m.mentionAll, m.changeVersion, m.conversation.syncGeneration)
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

    @Query("""
            SELECT new com.nexachat.message.dto.ReplyRefRow(
                       m.id, u.id, u.displayName, m.type, m.content, m.deletedAt)
            FROM Message m JOIN m.sender u
            WHERE m.id IN :ids
              AND EXISTS (SELECT 1 FROM ConversationMember member
                          WHERE member.conversation.id = m.conversation.id AND member.user.id = :userId
                            AND m.createdAt >= member.joinedAt)
            """)
    List<ReplyRefRow> findSyncReplyRefsByIdIn(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);
}
