package com.nexachat.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.core.common.ApiError

/** Maps an [ApiError] to its user-facing string resource (en/vi). */
@Composable
fun apiErrorMessage(error: ApiError): String = stringResource(AuthErrorIds.messageId(error))
