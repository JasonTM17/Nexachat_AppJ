package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass

/**
 * `GET`/`PATCH /api/v1/users/me/settings` (F3). Only the privacy flag ships in
 * this release; the response is the same shape for both verbs.
 */
@JsonClass(generateAdapter = true)
data class UserSettingsDto(
    val showPresence: Boolean? = null,
)

/** `PATCH /users/me/settings` body — a partial update, so only the flag. */
@JsonClass(generateAdapter = true)
data class UpdateUserSettingsRequest(
    val showPresence: Boolean,
)
