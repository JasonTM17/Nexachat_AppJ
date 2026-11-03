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
    fun resolvedLastContent(): String? = lastMessage?.content ?: lastMessageContent

    fun resolvedLastSender(): String? =
        lastMessage?.senderDisplayName ?: lastMessage?.senderName ?: lastMessageSenderName

    fun resolvedLastAt(): String? = lastMessage?.createdAt ?: lastMessageAt

    fun resolvedOtherName(myUserId: String?): String? =
        otherUser?.displayName ?: otherUserName
            ?: members.firstOrNull { it.userId != null && it.userId != myUserId }?.displayName

    fun resolvedOtherAvatar(): String? = otherUser?.avatarUrl ?: otherUserAvatar
}

/** Nested `lastMessage` projection variant for [ConversationInboxDto]. */
@JsonClass(generateAdapter = true)
data class InboxLastMessageDto(
    val content: String? = null,
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
