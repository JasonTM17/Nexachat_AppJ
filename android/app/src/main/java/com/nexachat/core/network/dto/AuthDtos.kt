package com.nexachat.core.network.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * Auth REST DTOs — shapes from plan §10.3 (camelCase wire names match the
 * Kotlin property names, so no `@Json` overrides are needed).
 * `accessTokenExpiresIn` is seconds (e.g. 900); callers convert to epoch ms.
 */

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val phone: String,
    val password: String,
    val displayName: String,
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val phone: String,
    val password: String,
)

@JsonClass(generateAdapter = true)
data class RefreshRequest(
    val refreshToken: String,
)

@JsonClass(generateAdapter = true)
data class LogoutRequest(
    val refreshToken: String,
)

@JsonClass(generateAdapter = true)
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
    /** Sent so the server keeps THIS session alive while revoking the rest. */
    val refreshToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val phone: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

/** `POST /users/me/avatar` body — the stored file's served path. */
@JsonClass(generateAdapter = true)
data class AvatarResponse(
    val avatarUrl: String,
)

@JsonClass(generateAdapter = true)
data class AuthResponse(
    val user: UserDto,
    val accessToken: String,
    /** Seconds until the access token expires (server sends e.g. 900). */
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
)

/** Uniform error envelope: `{"error":{"code":"…","message":"…","details":{…}}}`. */
@JsonClass(generateAdapter = true)
data class ErrorResponse(
    val error: Body? = null,
) {
    @JsonClass(generateAdapter = true)
    data class Body(
        val code: String? = null,
        val message: String? = null,
        val details: Map<String, Any?>? = null,
    )
}

private val errorAdapter by lazy { Moshi.Builder().build().adapter(ErrorResponse::class.java) }

/**
 * Extracts `error.code` from a raw error-body JSON string. Returns `null` for
 * null/blank/malformed bodies — callers switch on `code`, never on `message`.
 */
fun errorCodeOf(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return try {
        errorAdapter.fromJson(json)?.error?.code
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
