package com.nexachat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.core.common.ApiError

/**
 * [ApiError] → user-facing strings for the chat/inbox surfaces. Same variant
 * set as the auth mapper; kept feature-local like the contacts mapper so the
 * wording can diverge per surface without cross-feature edits.
 */
@Composable
internal fun chatErrorMessage(error: ApiError): String =
    stringResource(ChatErrorIds.messageId(error))
