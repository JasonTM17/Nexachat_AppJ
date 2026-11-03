package com.nexachat.core.common

/**
 * Domain-facing error vocabulary produced by parsing the server's error
 * envelope (`{"error":{"code":…}}`, plan §10.1). UI maps each variant to a
 * string resource — it never parses `message`.
 */
sealed interface ApiError {
    /** 401 `INVALID_CREDENTIALS` (also reused for `WRONG_PASSWORD`). */
    data object InvalidCredentials : ApiError

    /** 409 `PHONE_EXISTS` on register. */
    data object PhoneExists : ApiError

    /** 429 / `RATE_LIMITED` (Bucket4j on the server). */
    data object RateLimited : ApiError

    /** Transport failure — no HTTP response at all. */
    data class Network(val message: String? = null) : ApiError

    /** Anything else; keeps the raw server `code` for logging/debug. */
    data class Unknown(val code: String? = null) : ApiError

    companion object {
        fun from(httpStatus: Int, serverCode: String?): ApiError = when {
            serverCode == "INVALID_CREDENTIALS" || serverCode == "WRONG_PASSWORD" ->
                InvalidCredentials
            serverCode == "PHONE_EXISTS" -> PhoneExists
            serverCode == "RATE_LIMITED" || httpStatus == 429 -> RateLimited
            else -> Unknown(serverCode)
        }
    }
}
