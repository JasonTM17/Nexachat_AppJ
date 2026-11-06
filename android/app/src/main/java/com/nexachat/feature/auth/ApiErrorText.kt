package com.nexachat.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.R
import com.nexachat.core.common.ApiError

/** Maps an [ApiError] to its user-facing string resource (en/vi). */
@Composable
fun apiErrorMessage(error: ApiError): String = when (error) {
    is ApiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    is ApiError.PhoneExists -> stringResource(R.string.error_phone_exists)
    is ApiError.RateLimited -> stringResource(R.string.error_rate_limited)
    is ApiError.Network -> stringResource(R.string.error_network)
    is ApiError.Unknown -> stringResource(R.string.error_generic)
}
