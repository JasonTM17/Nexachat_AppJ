package com.nexachat.feature.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexachat.core.common.ApiError

/**
 * [ApiError] → user-facing strings for the group surfaces. Same variant set
 * as the other feature mappers; kept feature-local so wording can diverge
 * without cross-feature edits.
 */
@Composable
internal fun groupErrorMessage(error: ApiError): String = stringResource(GroupErrorIds.messageId(error))
