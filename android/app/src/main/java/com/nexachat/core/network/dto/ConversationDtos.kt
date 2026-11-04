package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass

/**
 * `GET /conversations` inbox row (plan §10.2: id + lastMessage + unreadCount).
 *
 * The frozen contract names the payload but not its exact field layout, so
 * every projection is tolerant: a nested `lastMessage` object OR flattened
 * `lastMessage*` fields; an `otherUser` object OR `otherUserName`/`otherUserAvatar`
 * OR the raw `members` list (the caller resolves "other" as member ≠ self).
 */
@JsonClass(generateAdapter = true)
data class ConversationInboxDto(
    val id: String,
    val type: String? = null,
    val title: String? = null,
    val lastMessageSeq: Long? = null,
    val lastMessage: InboxLastMessageDto? = null,
    val lastMessageContent: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int? = null,
    val otherUser: OtherUserDto? = null,
    val otherUserName: String? = null,
    val otherUserAvatar: String? = null,
    val members: List<ConversationMemberDto> = emptyList(),
) {
    fun resolvedLastContent(): String? =
        lastMessage?.content ?: lastMessage?.contentSnippet ?: lastMessageContent
            ?: lastMessage?.type

    fun resolvedLastSender(): String? =
        lastMessage?.senderDisplayName ?: lastMessage?.senderName ?: lastMessageSenderName

    fun resolvedLastAt(): String? = lastMessage?.createdAt ?: lastMessageAt

    /** Watermark for history paging — nested `lastMessage.seq` beats the flat field. */
    fun resolvedLastSeq(): Long? = lastMessage?.seq ?: lastMessageSeq

    fun resolvedOtherName(myUserId: String?): String? =
        otherUser?.displayName ?: otherUserName
            ?: members.firstOrNull { it.userId != null && it.userId != myUserId }?.displayName

    /**
     * The member-≠-self fallback is DIRECT-only — a group row must never
     * borrow a member's photo as its own avatar.
     */
    fun resolvedOtherAvatar(myUserId: String?): String? =
        otherUser?.avatarUrl ?: otherUserAvatar
            ?: members.firstOrNull { it.userId != null && it.userId != myUserId }
                ?.takeIf { type == null || type == "DIRECT" }?.avatarUrl
}

/**
 * Nested `lastMessage` projection variant for [ConversationInboxDto]. The
 * current server build sends `{seq, type, contentSnippet, senderId,
 * createdAt}`; [content] and the sender-name spellings cover earlier and
 * flattened variants.
 */
@JsonClass(generateAdapter = true)
data class InboxLastMessageDto(
    val seq: Long? = null,
    val type: String? = null,
    val content: String? = null,
    /** Current server spelling of the snippet field. */
    val contentSnippet: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val senderDisplayName: String? = null,
    val createdAt: String? = null,
)

/** Nested `otherUser` projection variant for [ConversationInboxDto]. */
@JsonClass(generateAdapter = true)
data class OtherUserDto(
    val userId: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

/**
 * Envelope variant for the inbox list when the server mirrors the
 * `{items, nextBeforeSeq, hasMore}` convention of the messages endpoint.
 * ConversationRepository tolerates a bare JSON array as well.
 */
@JsonClass(generateAdapter = true)
data class ConversationsPageDto(
    val items: List<ConversationInboxDto> = emptyList(),
    val nextBeforeSeq: Long? = null,
    val hasMore: Boolean = false,
)
