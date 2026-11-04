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

object OutboxState {
    const val SENDING = "SENDING"
    const val FAILED = "FAILED"
}

/**
 * Aggregated reaction summary per (message, emoji) — the shape BOTH wire
 * variants reduce to: REST embeds `{emoji, count, reactedByMe}` on the
 * message, `REACTION_UPDATED` pushes `{emoji, count, userIds}` (resolved to
 * `reactedByMe` at ingest). Stored pre-aggregated rather than per-user rows
 * because the REST summary cannot be decomposed back into user rows.
 * `conversationId` is denormalized so the chat flow queries without a join.
 */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["messageId", "emoji"],
    indices = [Index(value = ["conversationId"])],
)
data class MessageReactionEntity(
    val messageId: String,
    val conversationId: String,
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

/**
 * One pinned message per conversation (phase-10). `pinnedAt` is the
 * client-side write clock — it only orders the pin list (latest pin first
 * in the banner), never message history.
 */
@Entity(
    tableName = "pinned_messages",
    primaryKeys = ["conversationId", "messageId"],
)
data class PinnedMessageEntity(
    val conversationId: String,
    val messageId: String,
    val pinnedAt: Long,
)
