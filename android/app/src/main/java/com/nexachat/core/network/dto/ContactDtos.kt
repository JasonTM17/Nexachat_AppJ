package com.nexachat.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * User-discovery / contacts / direct-conversation REST DTOs (plan §10.2–10.3).
 * Wire names are camelCase and match the Kotlin properties, so no `@Json`
 * overrides are needed except where the server contract has two spellings.
 *
 * Tolerance notes:
 * - The plan documents a contact's id as `userId`; the phase brief uses
 *   `contactUserId`. Both are decoded so either server build parses cleanly.
 * - `POST /contacts` is documented as `{ "contact": {…} }` but may also arrive
 *   as a bare contact body — see [ContactEnvelopeDto].
 */

@JsonClass(generateAdapter = true)
data class UpdateProfileRequest(
    /** Optional per server: absent/null means "no change". */
    val displayName: String? = null,
)

@JsonClass(generateAdapter = true)
data class AddContactRequest(
    val phone: String,
)

@JsonClass(generateAdapter = true)
data class UpdateContactRequest(
    /** Alias nickname for this contact; null clears it. */
    val aliasName: String? = null,
)

@JsonClass(generateAdapter = true)
data class ContactDto(
    @Json(name = "contactUserId") val contactUserId: String? = null,
    /** Plan §10.3 spelling of the same field — accepted as a fallback. */
    @Json(name = "userId") val userId: String? = null,
    val phone: String? = null,
    val displayName: String? = null,
    val aliasName: String? = null,
    val createdAt: String? = null,
) {
    /** The contact's user id regardless of which spelling the server sent. */
    val resolvedUserId: String? get() = contactUserId ?: userId
}

/**
 * `POST`/`PATCH /contacts` response: plan §10.3 wraps the contact under
 * `contact`, but a bare [ContactDto] body is also tolerated — in that case the
 * flat fields here carry the payload and [unwrap] rebuilds the DTO.
 */
@JsonClass(generateAdapter = true)
data class ContactEnvelopeDto(
    val contact: ContactDto? = null,
    @Json(name = "contactUserId") val contactUserId: String? = null,
    @Json(name = "userId") val userId: String? = null,
    val phone: String? = null,
    val displayName: String? = null,
    val aliasName: String? = null,
    val createdAt: String? = null,
) {
    fun unwrap(): ContactDto =
        contact ?: ContactDto(contactUserId, userId, phone, displayName, aliasName, createdAt)
}

@JsonClass(generateAdapter = true)
data class DirectConversationRequest(
    /** XOR with [phone] — the server 400s when both are present. */
    val userId: String? = null,
    val phone: String? = null,
)

@JsonClass(generateAdapter = true)
data class ConversationMemberDto(
    val userId: String? = null,
    val displayName: String? = null,
    val phone: String? = null,
)

@JsonClass(generateAdapter = true)
data class DirectConversationResponse(
    val id: String,
    val type: String? = null,
    val members: List<ConversationMemberDto> = emptyList(),
    val createdAt: String? = null,
)
