package com.nexachat.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities — Room IS the source of truth (plan §12.2): the UI renders
 * only what these tables hold, so a process restart shows history instantly.
 *
 * [MessageEntity] mirrors the server `messages` row (§9.2) plus denormalized
 * sender display name for cheap list rendering. Ordering is by `seq`
 * (server-assigned BIGSERIAL — never by client clock).
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "seq"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val seq: Long,
    val senderId: String,
    val senderDisplayName: String?,
    val type: String,
    val content: String?,
    val clientMessageId: String?,
    val replyToId: String?,
    /**
     * Denormalized embedded `replyTo` preview (phase-10 quote render) — kept
     * so the quote block still shows sender/snippet when the original
     * message is outside the loaded history window.
     */
    val replyToSenderName: String?,
    val replyToSnippet: String?,
    /** Epoch milliseconds (DTO `createdAt` ISO instant, converted at ingest). */
    val createdAt: Long,
    /** Re-encoded `attachments` array JSON; null when empty. */
    val attachmentsJson: String?,
    /** `editedAt` ingest; null while the content is original. */
    val editedAtEpochMs: Long? = null,
    /**
     * Soft-delete marker. A deleted row keeps its `seq` (history continuity);
     * `content` is blanked and attachments detached at ingest.
     */
    val deletedAtEpochMs: Long? = null,
)

/**
 * Inbox row. `otherUser*` is the resolved peer for DIRECT conversations
 * (member ≠ me); `title` serves GROUP conversations. `unreadCount` is the
 * server value from `GET /conversations`, adjusted locally between refreshes.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String?,
    val lastMessageSeq: Long,
    val lastMessageContent: String?,
    val lastMessageSenderName: String?,
    /** Epoch milliseconds; null while the conversation has no messages. */
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val otherUserName: String?,
    val otherUserAvatar: String?,
    /** GROUP avatar URL set by an admin (DIRECT rows leave it null). */
    val avatarUrl: String?,
    /**
     * Highest OTHER member's read watermark, pushed via MESSAGE_READ events.
     * Client-only — no REST field feeds it, so refresh upserts must merge-
     * preserve rather than overwrite (see ConversationRepository). Renders
     * "Đã xem"/"Seen" on DIRECT bubbles where `seq <= peerReadSeq` (§FR-C4).
     */
    val peerReadSeq: Long = 0,
    /**
     * My own member role as the inbox payload reports it ("ADMIN"/"MEMBER";
     * null for DIRECT rows and payloads without members[]). Drives client-
     * side affordance gating such as GROUP pin (server still enforces).
     */
    val myRole: String? = null,
    /**
     * Peer user id for DIRECT rows — keys the presence map
     * (`PresenceRepository`), so the chat header can resolve peer state.
     */
    val otherUserId: String? = null,
    /**
     * True when the conversation's last message is soft-deleted; the inbox
     * renders a neutral "message deleted" snippet instead of stale content.
     */
    val lastMessageDeleted: Boolean = false,
)

/**
 * Optimistic-send queue (plan §12.2 send pipeline). A row lives from
 * `send()` until the matching `clientMessageId` is acked by MESSAGE_CREATED
 * (or answered by the REST fallback) — it is deleted on success, never
 * flipped to a "SENT" state, so the SENDING/FAILED pair stays exhaustive.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val clientMessageId: String,
    val conversationId: String,
    val type: String,
    val content: String,
    val replyToId: String?,
    /** [OutboxState.SENDING] or [OutboxState.FAILED] — kept as String to skip a TypeConverter. */
    val state: String,
    /** Client clock — only used to order the pending queue, never to sort history. */
    val createdAt: Long,
    /**
     * Re-encoded `attachments` JSON array ([com.nexachat.core.network.dto.AttachmentDto]),
     * empty string when none. Persisted so a retry/flush can re-derive both the
     * `attachmentIds` wire field and the bubble's render model after restart.
     */
    val attachmentsJson: String = "",
)
