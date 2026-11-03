package com.nexachat.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.AppLocaleManager
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import com.nexachat.feature.contacts.ErrorText
import com.nexachat.feature.contacts.contactErrorMessage

/**
 * Own profile: avatar placeholder, editable displayName (PATCH /users/me),
 * read-only phone, change-password stub (no screen exists in feature/auth yet
 * — the row stays disabled until it does), a language entry into
 * `settings/language`, and logout. Logout navigation is global:
 * SessionEvents → AppNavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading -> LoadingState()
                state.user == null -> ErrorState(
                    message = contactErrorMessage(state.error ?: ApiError.Unknown()),
                    onRetry = viewModel::load,
                )
                else -> ProfileContent(
                    state = state,
                    onStartEdit = viewModel::startEditName,
                    onNameChange = viewModel::onNameInputChange,
                    onSaveName = viewModel::saveDisplayName,
                    onCancelEdit = viewModel::cancelEditName,
                    onOpenLanguage = onOpenLanguage,
                    onLogout = viewModel::logout,
                )
            }
        }
    }
}

@Composable
private fun ProfileContent(
    state: ProfileViewModel.UiState,
    onStartEdit: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenLanguage: () -> Unit,
    onLogout: () -> Unit,
) {
    val user = state.user ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Letter-circle placeholder until avatar upload lands (plan P4).
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        if (state.editingName) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                OutlinedTextField(
                    value = state.nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.auth_display_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveName() }),
                )
                state.saveError?.let { ErrorText(it, Modifier.padding(top = 4.dp)) }
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onCancelEdit, enabled = !state.saving) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = onSaveName,
                        enabled = state.nameInput.isNotBlank() && !state.saving,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onStartEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.cd_edit_name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = user.phone,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        // Settings entry: per-app language (phase-10). The trailing label is
        // the current pick; apply() recreates the activity, so it can never
        // render stale after a change.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLanguage)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    languageOptionLabel(
                        AppLocaleManager.current(LocalContext.current),
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Stub: no change-password screen exists yet (feature/auth scope).
        Text(
            text = stringResource(R.string.profile_change_password),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.5f),
        )

        TextButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.action_logout),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
