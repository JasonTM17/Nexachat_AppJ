package com.nexachat.core.network.stomp.dto

import com.squareup.moshi.JsonClass

/**
 * Wire DTOs for the STOMP event contract (plan §11.2).
 *
 * Every server push on `/topic/conversations.{id}` and `/user/queue/inbox`
 * uses the same envelope:
 *   { "eventType": "MESSAGE_CREATED", "conversationId": "c-42",
 *     "serverTs": "2026-11-03T09:12:04Z", "payload": { … } }
 *
 * [WsEnvelope.payload] is kept as a raw map — the concrete payload type
 * depends on [WsEnvelope.eventType] and is decoded a second time by
 * RealtimeSession (re-encoding the map through the typed adapter, which keeps
 * numeric coercion identical to a direct parse).
 */
@JsonClass(generateAdapter = true)
data class WsEnvelope(
    val eventType: String? = null,
    val conversationId: String? = null,
    val serverTs: String? = null,
    val payload: Map<String, Any?>? = null,
)

/** `USER_TYPING` payload — the plan specifies an empty SEND body client-side. */
@JsonClass(generateAdapter = true)
data class TypingSignalDto(
    val userId: String? = null,
    val displayName: String? = null,
)

/**
 * `MESSAGE_READ` payload — a read-watermark update for one member. The plan
 * table says `{upToSeq}`; both spellings are tolerated for the reader id and
 * the watermark.
 */
@JsonClass(generateAdapter = true)
data class ReadUpdateDto(
    val userId: String? = null,
    val readerId: String? = null,
    val upToSeq: Long? = null,
    val lastReadSeq: Long? = null,
) {
    val resolvedUserId: String? get() = userId ?: readerId
    val resolvedSeq: Long? get() = upToSeq ?: lastReadSeq
}

/** `/user/queue/errors` body — a rejected SEND (plan §11.2). */
@JsonClass(generateAdapter = true)
data class SendErrorDto(
    val code: String? = null,
    val clientMessageId: String? = null,
    val message: String? = null,
)
