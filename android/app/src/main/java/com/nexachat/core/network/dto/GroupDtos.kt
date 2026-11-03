package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass

/**
 * Group-conversation REST DTOs (phase-08 group chat; plan §10.2 endpoint
 * table). The server endpoints land in a parallel workstream, so every
 * response field beyond `id` is optional — callers resolve fallbacks.
 */

/** `POST /conversations/group` body: `{title, memberIds[]}` → 201 ConversationDto. */
@JsonClass(generateAdapter = true)
data class CreateGroupRequest(
    val title: String,
    val memberIds: List<String>,
)

/** `POST /conversations/{id}/members` body: `{userIds[]}` → 204. */
@JsonClass(generateAdapter = true)
data class AddGroupMembersRequest(
    val userIds: List<String>,
)

/**
 * `PATCH /conversations/{id}` body: `{title?, avatarUrl?}` — absent/null
 * fields mean "no change" (same convention as UpdateProfileRequest).
 */
@JsonClass(generateAdapter = true)
data class UpdateConversationRequest(
    val title: String? = null,
    val avatarUrl: String? = null,
)

/**
 * Single-conversation payload returned by `POST /conversations/group` (and
 * the documented `GET/PATCH /conversations/{id}`). Mirrors the member
 * projection of [ConversationInboxDto]; `members[].role` may be absent on
 * early server builds (see [ConversationMemberDto]).
 */
@JsonClass(generateAdapter = true)
data class ConversationDto(
    val id: String,
    val type: String? = null,
    val title: String? = null,
    val avatarUrl: String? = null,
    val members: List<ConversationMemberDto> = emptyList(),
    val createdAt: String? = null,
)
