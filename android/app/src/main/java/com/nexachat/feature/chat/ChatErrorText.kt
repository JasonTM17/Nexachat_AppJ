package com.nexachat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.R
import com.nexachat.core.common.ApiError

/**
 * [ApiError] → user-facing strings for the chat/inbox surfaces. Same variant
 * set as the auth mapper; kept feature-local like the contacts mapper so the
 * wording can diverge per surface without cross-feature edits.
 */
@Composable
internal fun chatErrorMessage(error: ApiError): String = when (error) {
    ApiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    ApiError.PhoneExists -> stringResource(R.string.error_phone_exists)
    ApiError.RateLimited -> stringResource(R.string.error_rate_limited)
    is ApiError.Network -> stringResource(R.string.error_network)
    is ApiError.Unknown -> stringResource(R.string.error_generic)
}
