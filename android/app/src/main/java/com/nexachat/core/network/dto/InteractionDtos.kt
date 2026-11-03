package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass

/**
 * Reaction/pin wire DTOs (phase-10 message interactions, plan §10.2).
 *
 * Field names follow the project's camelCase convention — the same Moshi
 * `@JsonClass(generateAdapter = true)` style as [MessageDtos]. The server
 * lands in a parallel workstream, so every response field stays optional
 * and both documented spellings are tolerated where the contract is loose:
 * `reactedByMe` (REST summary) OR `userIds` (WS push) on a reaction summary.
 */

/**
 * One reaction summary row: `{emoji, count, byMe}` embedded on message
 * responses (shipped server spelling; `reactedByMe` tolerated), `{emoji,
 * count, userIds}` inside `REACTION_UPDATED`. Callers resolve
 * `reactedByMe ?: byMe ?: userIds.contains(myId)`.
 */
@JsonClass(generateAdapter = true)
data class ReactionSummaryDto(
    val emoji: String? = null,
    val count: Int? = null,
    val reactedByMe: Boolean? = null,
    /** REST history spelling the server ships (`ReactionView.byMe`). */
    val byMe: Boolean? = null,
    /** REACTION_UPDATED spelling — the reacting user ids. */
    val userIds: List<String> = emptyList(),
)

/** `POST /messages/{id}/reactions` body — one emoji per call, toggle semantics. */
@JsonClass(generateAdapter = true)
data class ReactionRequest(
    val emoji: String,
)

/** `REACTION_UPDATED` payload — also the `POST /reactions` response shape. */
@JsonClass(generateAdapter = true)
data class ReactionUpdateDto(
    val messageId: String? = null,
    val reactions: List<ReactionSummaryDto> = emptyList(),
)

/** `POST /conversations/{id}/pins` body. */
@JsonClass(generateAdapter = true)
data class PinRequest(
    val messageId: String,
)

/** `PIN_UPDATED` payload — `pinned` tells add vs remove. */
@JsonClass(generateAdapter = true)
data class PinUpdateDto(
    val messageId: String? = null,
    val pinned: Boolean? = null,
)
