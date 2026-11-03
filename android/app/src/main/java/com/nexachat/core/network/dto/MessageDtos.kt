package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass

/**
 * Message REST/WS DTOs (plan §10.3 messages example + §11.2 MESSAGE_CREATED).
 *
 * The contract is frozen but the server implementation lands in a parallel
 * workstream, so the DTO is tolerant of the two spellings seen across plan
 * documents: a flat `senderId`/`senderDisplayName` pair or a nested `sender`
 * object; `replyTo` as an embedded preview or a plain `replyToId`.
 */
@JsonClass(generateAdapter = true)
data class MessageDto(
    val id: String,
    val seq: Long? = null,
    val conversationId: String? = null,
    val senderId: String? = null,
    val senderDisplayName: String? = null,
    /** Alternative nested shape: `{ "sender": { "id", "displayName" } }`. */
    val sender: SenderRefDto? = null,
    /** Idempotency key the sender generated (plan §11.3.3 dedup). */
    val clientMessageId: String? = null,
    val type: String? = null,
    val content: String? = null,
    val replyTo: ReplyRefDto? = null,
    val replyToId: String? = null,
    /**
     * Embedded reaction summaries (phase-10). Nullable — `null` means the
     * field was absent and callers must NOT wipe locally-known summaries;
     * an explicit empty list means "no reactions".
     */
    val reactions: List<ReactionSummaryDto>? = null,
    val attachments: List<AttachmentDto> = emptyList(),
    /** ISO-8601 instant, e.g. `2026-11-03T09:12:04Z`. */
    val createdAt: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null,
) {
    val resolvedSenderId: String? get() = senderId ?: sender?.id
    val resolvedSenderName: String? get() = senderDisplayName ?: sender?.displayName
    val resolvedReplyToId: String? get() = replyTo?.id ?: replyToId
    val resolvedType: String get() = type ?: TYPE_TEXT

    companion object {
        const val TYPE_TEXT = "TEXT"
        const val TYPE_IMAGE = "IMAGE"
        const val TYPE_VIDEO = "VIDEO"
        const val TYPE_AUDIO = "AUDIO"
        const val TYPE_FILE = "FILE"
    }
}

@JsonClass(generateAdapter = true)
data class SenderRefDto(
    val id: String? = null,
    val displayName: String? = null,
)

/**
 * Embedded `replyTo` preview (phase-10 contract: `{id, senderId, senderName,
 * snippet}`). The earlier `{id, senderDisplayName, content}` spelling stays
 * tolerated — resolve through the getters.
 */
@JsonClass(generateAdapter = true)
data class ReplyRefDto(
    val id: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val senderDisplayName: String? = null,
    val snippet: String? = null,
    val content: String? = null,
) {
    val resolvedSenderName: String? get() = senderName ?: senderDisplayName
    val resolvedSnippet: String? get() = snippet ?: content
}

/** `GET /conversations/{id}/messages` cursor page (plan §10.3). */
@JsonClass(generateAdapter = true)
data class MessagePageDto(
    val items: List<MessageDto> = emptyList(),
    val nextBeforeSeq: Long? = null,
    val hasMore: Boolean = false,
)

/**
 * `POST /conversations/{id}/messages` REST body — the SAME shape the STOMP
 * SEND to `/app/conversations/{id}/messages` carries (plan §11.2), so this
 * class doubles as the WS send payload.
 */
@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    val clientMessageId: String,
    val type: String = MessageDto.TYPE_TEXT,
    val content: String? = null,
    val replyToId: String? = null,
    val attachmentIds: List<String> = emptyList(),
)

/** `POST /conversations/{id}/read` body — read watermark (plan §10.2). */
@JsonClass(generateAdapter = true)
data class ReadRequest(
    val upToSeq: Long,
)
