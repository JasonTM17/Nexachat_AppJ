package com.nexachat.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexachat.core.ui.components.Avatar
import com.nexachat.R
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.absoluteMediaUrl
import com.nexachat.feature.media.rememberAuthedImageLoader

/**
 * [ApiError] → user-facing strings for the contacts/search flows. Distinct
 * from the auth mapper because the same variants mean different things here:
 * `PHONE_EXISTS`/`ALREADY_CONTACT` is "already a contact", and 404
 * `USER_NOT_FOUND` is a normal "nobody with that phone" state.
 */
@Composable
internal fun contactErrorMessage(error: ApiError): String = when (error) {
    ApiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    ApiError.PhoneExists -> stringResource(R.string.error_already_contact)
    ApiError.RateLimited -> stringResource(R.string.error_rate_limited)
    is ApiError.Network -> stringResource(R.string.error_network)
    is ApiError.Unknown -> when (error.code) {
        "USER_NOT_FOUND" -> stringResource(R.string.error_user_not_found)
        else -> stringResource(R.string.error_generic)
    }
}

/**
 * Avatar: server image via the authed loader when [avatarUrl] is set,
 * else the letter-circle fallback.
 */
@Composable
internal fun InitialAvatar(
    name: String,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Avatar(
        name = name,
        avatarUrl = avatarUrl,
        modifier = modifier,
        size = size,
    )
}

@Composable
internal fun UserRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(title, avatarUrl, size = 48.dp)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ErrorText(error: ApiError, modifier: Modifier = Modifier) {
    Text(
        text = contactErrorMessage(error),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * Body of the add-by-phone sheet: phone input → find → result card → add.
 * [ContactsViewModel.UiState.addDone] drives dismissal in the host screen.
 */
@Composable
internal fun AddByPhoneContent(
    state: ContactsViewModel.UiState,
    onPhoneChange: (String) -> Unit,
    onFind: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 24.dp)) {
        Text(
            text = stringResource(R.string.add_by_phone_title),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = state.addPhone,
            onValueChange = onPhoneChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            label = { Text(stringResource(R.string.auth_phone_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onFind() }),
        )
        state.addError?.let { ErrorText(it, Modifier.padding(top = 8.dp)) }
        state.addResult?.let { user ->
            UserRow(
                title = user.displayName,
                subtitle = user.phone,
                onClick = {},
                modifier = Modifier.padding(top = 8.dp),
                avatarUrl = user.avatarUrl,
            )
        }
        if (state.addDone) {
            Text(
                text = stringResource(R.string.contact_added),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Button(
                onClick = if (state.addResult == null) onFind else onAdd,
                enabled = state.addPhone.isNotBlank() && !state.addSearching && !state.adding,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
            ) {
                if (state.addSearching || state.adding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        stringResource(
                            if (state.addResult == null) {
                                R.string.action_search
                            } else {
                                R.string.action_add_contact
                            },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Body of the detail sheet shared by ContactsScreen and UserSearchScreen.
 * When [ContactsViewModel.UiState.detailContact] is non-null the user is a
 * contact (alias edit + remove); otherwise an "Add contact" action is shown.
 */
@Composable
internal fun ContactDetailContent(
    state: ContactsViewModel.UiState,
    onAliasChange: (String) -> Unit,
    onSaveAlias: () -> Unit,
    onRemove: () -> Unit,
    onAddContact: () -> Unit,
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = state.detailUser ?: return
    val contact = state.detailContact
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InitialAvatar(user.displayName, user.avatarUrl, size = 64.dp)
        Text(
            text = contact?.primaryLabel ?: user.displayName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = user.phone,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.detailError?.let { ErrorText(it, Modifier.padding(top = 8.dp)) }

        if (contact != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.aliasInput,
                    onValueChange = onAliasChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.contact_alias_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveAlias() }),
                )
                TextButton(
                    onClick = onSaveAlias,
                    enabled = !state.detailBusy,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        Button(
            onClick = onMessage,
            enabled = !state.detailBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            if (state.detailBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.action_message))
            }
        }

        if (contact == null) {
            TextButton(
                onClick = onAddContact,
                enabled = !state.detailBusy,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.action_add_contact))
            }
        } else {
            TextButton(
                onClick = onRemove,
                enabled = !state.detailBusy,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_remove_contact),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
