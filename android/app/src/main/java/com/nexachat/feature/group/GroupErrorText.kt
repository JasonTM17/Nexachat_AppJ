package com.nexachat.feature.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.R
import com.nexachat.core.common.ApiError

/**
 * [ApiError] → user-facing strings for the group surfaces. Same variant set
 * as the other feature mappers; kept feature-local so wording can diverge
 * without cross-feature edits.
 */
@Composable
internal fun groupErrorMessage(error: ApiError): String = when (error) {
    ApiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    ApiError.PhoneExists -> stringResource(R.string.error_phone_exists)
    ApiError.RateLimited -> stringResource(R.string.error_rate_limited)
    is ApiError.Network -> stringResource(R.string.error_network)
    is ApiError.Unknown -> stringResource(R.string.error_generic)
}
