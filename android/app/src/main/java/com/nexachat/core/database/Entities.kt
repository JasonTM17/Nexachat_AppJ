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
)

object OutboxState {
    const val SENDING = "SENDING"
    const val FAILED = "FAILED"
}
