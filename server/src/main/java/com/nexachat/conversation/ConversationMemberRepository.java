package com.nexachat.conversation;

import com.nexachat.conversation.dto.ConversationMemberView;
import com.nexachat.conversation.dto.InboxConversationRow;
import com.nexachat.conversation.dto.InboxMemberView;
import com.nexachat.conversation.dto.MemberFlagsRow;
import com.nexachat.conversation.dto.MemberResponse;
import com.nexachat.conversation.dto.UnreadCountRow;
import com.nexachat.presence.DirectPeerPresenceRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMemberId> {

    /**
     * Member read model for the conversation response: JOIN {@code users} once
     * and return the DTO projection (plan §9.3.7). Ordered by user id so the
     * array order is stable regardless of insert order.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.ConversationMemberView(
                       u.id, u.displayName, u.phone)
            FROM ConversationMember m JOIN m.user u
            WHERE m.conversation.id = :conversationId
            ORDER BY u.id
            """)
    List<ConversationMemberView> findMemberViews(@Param("conversationId") UUID conversationId);

    @Query("""
            SELECT m.conversation.id FROM ConversationMember m
            JOIN m.conversation c
            WHERE m.user.id = :userId AND c.type = com.nexachat.conversation.Conversation.Type.GROUP
            """)
    List<UUID> findGroupConversationIdsForUser(@Param("userId") UUID userId);

    /**
     * THE authorization primitive for message traffic (plan §11.3.2): used by
     * the STOMP SUBSCRIBE/SEND interceptors and every message service method.
     */
    boolean existsByConversation_IdAndUser_Id(UUID conversationId, UUID userId);

    /**
     * Monotonic read-watermark advance: a watermark never moves backwards, so
     * an out-of-order or replayed {@code upToSeq} cannot "un-read" messages.
     *
     * @return 1 when the row was advanced, 0 when already at/past {@code seq}
     */
    @Modifying
    @Query("""
            UPDATE ConversationMember m SET m.lastReadSeq = :seq
            WHERE m.conversation.id = :conversationId
              AND m.user.id = :userId
              AND m.lastReadSeq < :seq
            """)
    int advanceReadWatermark(@Param("conversationId") UUID conversationId,
                             @Param("userId") UUID userId,
                             @Param("seq") long seq);

    /** Read back the persisted monotonic watermark for the post-commit event. */
    @Query("SELECT m.lastReadSeq FROM ConversationMember m "
            + "WHERE m.conversation.id = :conversationId AND m.user.id = :userId")
    long findReadWatermark(@Param("conversationId") UUID conversationId,
                           @Param("userId") UUID userId);

    /**
     * Monotonic delivery-watermark advance: a watermark never moves backwards,
     * so an out-of-order or replayed {@code upToSeq} cannot un-deliver messages.
     *
     * @return 1 when the row was advanced, 0 when already at/past {@code seq}
     */
    @Modifying
    @Query("""
            UPDATE ConversationMember m SET m.lastDeliveredSeq = :seq
            WHERE m.conversation.id = :conversationId
              AND m.user.id = :userId
              AND m.lastDeliveredSeq < :seq
            """)
    int advanceDeliveredWatermark(@Param("conversationId") UUID conversationId,
                                  @Param("userId") UUID userId,
                                  @Param("seq") long seq);

    /** Read back the persisted monotonic delivery watermark for post-commit event. */
    @Query("SELECT m.lastDeliveredSeq FROM ConversationMember m "
            + "WHERE m.conversation.id = :conversationId AND m.user.id = :userId")
    long findDeliveredWatermark(@Param("conversationId") UUID conversationId,
                                @Param("userId") UUID userId);

    /** Member id list for broadcast fan-out (inbox pushes). */
    @Query("SELECT m.user.id FROM ConversationMember m WHERE m.conversation.id = :conversationId")
    List<UUID> findMemberUserIds(@Param("conversationId") UUID conversationId);

    /**
     * Members whose mute is currently effective: {@code muted = true} and either
     * forever ({@code mutedUntil IS NULL}) or not yet expired. Used by push
     * dispatch so an expired mute starts notifying again without a cleanup job.
     */
    @Query("""
            SELECT m.user.id
            FROM ConversationMember m
            WHERE m.conversation.id = :conversationId
              AND m.muted = true
              AND (m.mutedUntil IS NULL OR m.mutedUntil > :now)
            """)
    List<UUID> findMutedMemberIds(@Param("conversationId") UUID conversationId,
                                  @Param("now") Instant now);

    /**
     * The caller's own mute/archive/pin flags for a set of conversations,
     * carried by delta sync so a preference changed on one device reaches the
     * others.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.MemberFlagsRow(
                       m.conversation.id, m.muted, m.mutedUntil, m.archived,
                       m.pinned, m.pinnedAt)
            FROM ConversationMember m
            WHERE m.user.id = :userId AND m.conversation.id IN :conversationIds
            """)
    List<MemberFlagsRow> findMemberFlags(@Param("userId") UUID userId,
                                         @Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * The caller's current pin count — the per-user ceiling check. Serialized
     * by the user-row lock taken in {@code ConversationPinService}.
     */
    @Query("SELECT COUNT(m) FROM ConversationMember m "
            + "WHERE m.user.id = :userId AND m.pinned = true")
    long countPinnedByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT DISTINCT peer.user.id
            FROM ConversationMember own, ConversationMember peer
            WHERE own.conversation.id = peer.conversation.id
              AND own.user.id = :userId
              AND own.conversation.type = com.nexachat.conversation.Conversation.Type.DIRECT
              AND peer.user.id <> :userId
            """)
    List<UUID> findDirectPeerUserIds(@Param("userId") UUID userId);

    @Query("""
            SELECT new com.nexachat.presence.DirectPeerPresenceRow(
                       own.conversation.id, peer.user.id, peer.user.showPresence, peer.user.lastSeenAt)
            FROM ConversationMember own, ConversationMember peer
            WHERE own.conversation.id = peer.conversation.id
              AND own.user.id = :userId
              AND own.conversation.type = com.nexachat.conversation.Conversation.Type.DIRECT
              AND own.conversation.id IN :conversationIds
              AND peer.user.id <> :userId
            """)
    List<DirectPeerPresenceRow> findDirectPeerPresence(
            @Param("userId") UUID userId, @Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * Membership row fetch (group phase): the role-aware variant of
     * {@link #existsByConversation_IdAndUser_Id} — member ops need the actual
     * row to check {@code role = 'ADMIN'} and to delete on remove/leave.
     */
    Optional<ConversationMember> findByConversation_IdAndUser_Id(UUID conversationId, UUID userId);

    /** Serialize assignment-cap writes with membership deletion for this caller/conversation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ConversationMember m "
            + "WHERE m.conversation.id = :conversationId AND m.user.id = :userId")
    Optional<ConversationMember> findByConversation_IdAndUser_IdForUpdate(
            @Param("conversationId") UUID conversationId, @Param("userId") UUID userId);

    /** Role histogram for the last-admin invariant (group remove/leave guard). */
    long countByConversation_IdAndRole(UUID conversationId, ConversationMember.Role role);

    /** Total headcount — distinguishes "sole admin with members" from "last member". */
    long countByConversation_Id(UUID conversationId);

    /**
     * Role-carrying member read model for GROUP surfaces (group detail and the
     * {@code CONVERSATION_UPDATED} payload) — same single-JOIN projection style
     * as {@link #findMemberViews}, ordered by user id for a stable array.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.MemberResponse(
                       u.id, u.displayName, u.phone, u.avatarUrl, m.role, m.joinedAt)
            FROM ConversationMember m JOIN m.user u
            WHERE m.conversation.id = :conversationId
            ORDER BY u.id
            """)
    List<MemberResponse> findMembers(@Param("conversationId") UUID conversationId);

    /**
     * Successor pick for account-deletion admin hand-over: earliest
     * {@code joined_at}, tie-broken by user id (PostgreSQL ordering).
     */
    @Query(value = """
            SELECT user_id FROM conversation_members
            WHERE conversation_id = :conversationId AND user_id <> :excludeUserId
            ORDER BY joined_at ASC, user_id ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findSuccessorUserIdForAccountDeletion(
            @Param("conversationId") UUID conversationId,
            @Param("excludeUserId") UUID excludeUserId);

    /**
     * Inbox rows for {@code GET /conversations}: the caller's pinned rows
     * first, then everything by the denormalized {@code lastMessageSeq} (NULL
     * sorts last via {@code COALESCE}). {@code archived} selects the default
     * (non-archived) or archived view.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId AND m.archived = :archived
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversations(@Param("userId") UUID userId,
                                                      @Param("archived") boolean archived);

    /** Caller-scoped inbox filtered to conversations carrying one of that caller's labels. */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId AND m.archived = :archived
              AND EXISTS (SELECT a.id FROM ConversationLabelAssignment a
                          WHERE a.conversationId = c.id AND a.userId = :userId
                            AND a.labelId = :labelId)
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversationsByLabel(
            @Param("userId") UUID userId,
            @Param("archived") boolean archived,
            @Param("labelId") UUID labelId);

    /**
     * Keyset-paged inbox query with a unique final tie-breaker for stable
     * cursors. The cursor carries the pinned rank: when the cursor row was
     * pinned, every unpinned row follows it; when it was unpinned, only lower
     * activity rows follow. A unique id keeps the page boundary stable.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId
              AND m.archived = :archived
              AND (:cursorId IS NULL
                   OR (:cursorPinned = true AND m.pinned = false)
                   OR (m.pinned = :cursorPinned
                       AND (COALESCE(c.lastMessageSeq, 0) < :cursorSeq
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt < :cursorCreatedAt)
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt = :cursorCreatedAt
                                AND c.id > :cursorId))))
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversationsPage(
            @Param("userId") UUID userId,
            @Param("cursorPinned") Boolean cursorPinned,
            @Param("cursorSeq") Long cursorSeq,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("archived") boolean archived,
            Pageable pageable);

    /** Keyset-paged inbox variant that filters before applying the page limit. */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId
              AND m.archived = :archived
              AND EXISTS (SELECT a.id FROM ConversationLabelAssignment a
                          WHERE a.conversationId = c.id AND a.userId = :userId
                            AND a.labelId = :labelId)
              AND (:cursorId IS NULL
                   OR (:cursorPinned = true AND m.pinned = false)
                   OR (m.pinned = :cursorPinned
                       AND (COALESCE(c.lastMessageSeq, 0) < :cursorSeq
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt < :cursorCreatedAt)
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt = :cursorCreatedAt
                                AND c.id > :cursorId))))
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversationsPageByLabel(
            @Param("userId") UUID userId,
            @Param("cursorPinned") Boolean cursorPinned,
            @Param("cursorSeq") Long cursorSeq,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("archived") boolean archived,
            @Param("labelId") UUID labelId,
            Pageable pageable);

    /** Cursor-only form preserves the existing unlimited default when limit is omitted. */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId
              AND m.archived = :archived
              AND (:cursorId IS NULL
                   OR (:cursorPinned = true AND m.pinned = false)
                   OR (m.pinned = :cursorPinned
                       AND (COALESCE(c.lastMessageSeq, 0) < :cursorSeq
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt < :cursorCreatedAt)
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt = :cursorCreatedAt
                                AND c.id > :cursorId))))
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversationsAfterCursor(
            @Param("userId") UUID userId,
            @Param("cursorPinned") Boolean cursorPinned,
            @Param("cursorSeq") Long cursorSeq,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("archived") boolean archived);

    /** Cursor-only inbox variant for the optional label filter. */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxConversationRow(
                       c.id, c.type, c.title, c.avatarUrl, c.lastMessageId,
                       c.lastMessageSeq, c.createdAt, m.lastReadSeq,
                       m.muted, m.mutedUntil, m.archived, m.pinned, m.pinnedAt)
            FROM ConversationMember m JOIN m.conversation c
            WHERE m.user.id = :userId
              AND m.archived = :archived
              AND EXISTS (SELECT a.id FROM ConversationLabelAssignment a
                          WHERE a.conversationId = c.id AND a.userId = :userId
                            AND a.labelId = :labelId)
              AND (:cursorId IS NULL
                   OR (:cursorPinned = true AND m.pinned = false)
                   OR (m.pinned = :cursorPinned
                       AND (COALESCE(c.lastMessageSeq, 0) < :cursorSeq
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt < :cursorCreatedAt)
                            OR (COALESCE(c.lastMessageSeq, 0) = :cursorSeq
                                AND c.createdAt = :cursorCreatedAt
                                AND c.id > :cursorId))))
            ORDER BY m.pinned DESC, COALESCE(c.lastMessageSeq, 0) DESC,
                     c.createdAt DESC, c.id ASC
            """)
    List<InboxConversationRow> findInboxConversationsAfterCursorByLabel(
            @Param("userId") UUID userId,
            @Param("cursorPinned") Boolean cursorPinned,
            @Param("cursorSeq") Long cursorSeq,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("archived") boolean archived,
            @Param("labelId") UUID labelId);

    /** Batch member fetch for the inbox — one IN query, no N+1. */
    @Query("""
            SELECT new com.nexachat.conversation.dto.InboxMemberView(
                       m.conversation.id, u.id, u.displayName, u.phone, u.avatarUrl, m.role)
            FROM ConversationMember m JOIN m.user u
            WHERE m.conversation.id IN :conversationIds
            ORDER BY u.id
            """)
    List<InboxMemberView> findInboxMemberViews(@Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * Unread counts for a set of the caller's conversations in one grouped
     * query: {@code COUNT(messages.seq > lastReadSeq AND NOT deleted)} per
     * conversation (watermark model, plan §9.3.4). Conversations with zero
     * unread simply return no row.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.UnreadCountRow(
                       m.conversation.id, COUNT(msg))
            FROM ConversationMember m, Message msg
            WHERE m.user.id = :userId
              AND m.conversation.id IN :conversationIds
              AND msg.conversation.id = m.conversation.id
              AND msg.seq > m.lastReadSeq
              AND msg.deletedAt IS NULL
            GROUP BY m.conversation.id
            """)
    List<UnreadCountRow> countUnreadByConversation(@Param("userId") UUID userId,
                                                   @Param("conversationIds") Collection<UUID> conversationIds);
}
