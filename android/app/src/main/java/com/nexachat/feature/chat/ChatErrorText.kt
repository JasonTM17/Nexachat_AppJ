package com.nexachat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.R
import com.nexachat.core.common.ApiError
import com.nexachat.data.AttachmentRepository

/**
 * [ApiError] → user-facing strings for the chat/inbox surfaces. Same variant
 * set as the auth mapper; kept feature-local like the contacts mapper so the
 * wording can diverge per surface without cross-feature edits.
 */
/** Server `error.code` for the pins cap — avoids a magic string at the call site. */
internal const val ERROR_PIN_LIMIT = "PIN_LIMIT"

@Composable
internal fun chatErrorMessage(error: ApiError): String = when (error) {
    ApiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    ApiError.PhoneExists -> stringResource(R.string.error_phone_exists)
    ApiError.RateLimited -> stringResource(R.string.error_rate_limited)
    is ApiError.Network -> stringResource(R.string.error_network)
    is ApiError.Unknown -> when (error.code) {
        AttachmentRepository.ERROR_TOO_LARGE ->
            stringResource(R.string.error_attachment_too_large)
        // 409 on POST /pins — the conversation's pin cap (phase-10).
        ERROR_PIN_LIMIT -> stringResource(R.string.error_pin_limit)
        else -> stringResource(R.string.error_generic)
    }
}
