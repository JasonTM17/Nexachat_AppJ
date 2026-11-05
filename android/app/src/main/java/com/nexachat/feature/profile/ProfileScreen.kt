package com.nexachat.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nexachat.R
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.AppLocaleManager
import com.nexachat.core.common.absoluteMediaUrl
import com.nexachat.core.ui.ThemeMapper
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import com.nexachat.core.ui.components.SectionHeader
import com.nexachat.core.ui.components.SettingsRow
import com.nexachat.data.ThemeMode
import com.nexachat.feature.auth.PasswordField
import com.nexachat.feature.auth.apiErrorMessage
import com.nexachat.feature.contacts.ErrorText
import com.nexachat.feature.contacts.contactErrorMessage
import com.nexachat.feature.media.rememberAuthedImageLoader
import com.nexachat.summary.AiConsentRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiConsentEntryPoint {
    fun aiConsentRepository(): AiConsentRepository
}

@Composable
private fun <T> LiveData<T>.observeAsState(initial: T): State<T> {
    val state = remember { mutableStateOf(initial) }
    DisposableEffect(this) {
        val observer = Observer<T> { value ->
            if (value != null) {
                state.value = value
            }
        }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}

/**
 * Own profile: avatar upload (POST /users/me/avatar via the system image
 * picker, FR-A5), editable displayName (PATCH /users/me),
 * read-only phone, change-password dialog (PATCH /auth/password — the
 * current refresh token is passed so this device stays signed in while
 * every other session is revoked, FR-A6), a language entry into
 * `settings/language`, and logout. Logout navigation is global:
 * SessionEvents → AppNavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
    appSettingsViewModel: AppSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showPresence by viewModel.showPresence.collectAsStateWithLifecycle()
    val themeMode by appSettingsViewModel.themeMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val passwordChangedMessage = stringResource(R.string.profile_password_changed)
    val avatarErrorMessage = state.avatarError?.let { apiErrorMessage(it) }
    val saveErrorMessage = state.saveError?.let { apiErrorMessage(it) }
    val imageLoader = rememberAuthedImageLoader()

    val context = LocalContext.current
    val consentRepo = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AiConsentEntryPoint::class.java,
        ).aiConsentRepository()
    }
    val aiConsent by consentRepo.aiSummaryConsent().observeAsState(false)
    val consentError by consentRepo.error().observeAsState(null)
    var showAiConsentDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        consentRepo.refresh()
    }

    LaunchedEffect(consentError) {
        if (consentError != null) {
            snackbarHostState.showSnackbar(context.getString(R.string.settings_consent_update_failed))
        }
    }

    // System photo picker — image-only, no storage permission needed.
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.uploadAvatar(uri)
    }

    LaunchedEffect(state.passwordChanged) {
        if (state.passwordChanged) {
            viewModel.consumePasswordChanged()
            snackbarHostState.showSnackbar(passwordChangedMessage)
        }
    }

    LaunchedEffect(state.avatarError) {
        if (avatarErrorMessage != null) {
            viewModel.consumeAvatarError()
            snackbarHostState.showSnackbar(avatarErrorMessage)
        }
    }

    LaunchedEffect(state.saveError) {
        if (saveErrorMessage != null) {
            snackbarHostState.showSnackbar(saveErrorMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    showPresence = showPresence,
                    onTogglePresence = viewModel::setShowPresence,
                    themeMode = themeMode,
                    onSelectTheme = appSettingsViewModel::setThemeMode,
                    onStartEdit = viewModel::startEditName,
                    onNameChange = viewModel::onNameInputChange,
                    onSaveName = viewModel::saveDisplayName,
                    onCancelEdit = viewModel::cancelEditName,
                    onOpenLanguage = onOpenLanguage,
                    onOpenChangePassword = viewModel::openPasswordDialog,
                    onDismissPassword = viewModel::dismissPasswordDialog,
                    onOldPasswordChange = viewModel::onOldPasswordChange,
                    onNewPasswordChange = viewModel::onNewPasswordChange,
                    onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
                    onSubmitPassword = viewModel::submitPasswordChange,
                    onPickAvatar = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    imageLoader = imageLoader,
                    aiConsent = aiConsent,
                    onToggleAiConsent = { checked ->
                        if (checked) {
                            showAiConsentDialog = true
                        } else {
                            consentRepo.setConsent(false)
                        }
                    },
                    onLogout = viewModel::logout,
                    onLogoutAll = viewModel::logoutAll,
                )
            }
        }
    }

    if (showAiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showAiConsentDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(R.string.dialog_ai_consent_title)) },
            text = { Text(stringResource(R.string.dialog_ai_consent_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAiConsentDialog = false
                        consentRepo.setConsent(true)
                    },
                ) {
                    Text(
                        stringResource(R.string.dialog_ai_consent_confirm),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiConsentDialog = false }) {
                    Text(stringResource(R.string.dialog_ai_consent_dismiss))
                }
            },
        )
    }
}

@Composable
private fun ProfileContent(
    state: ProfileViewModel.UiState,
    showPresence: Boolean,
    onTogglePresence: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onStartEdit: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onDismissPassword: () -> Unit,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmitPassword: () -> Unit,
    onPickAvatar: () -> Unit,
    imageLoader: coil.ImageLoader,
    aiConsent: Boolean,
    onToggleAiConsent: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
) {
    val user = state.user ?: return
    var confirmLogoutAll by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val avatarDescription = stringResource(R.string.profile_change_avatar)
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onPickAvatar)
                        .semantics { contentDescription = avatarDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    if (user.avatarUrl != null) {
                        AsyncImage(
                            model = user.avatarUrl.absoluteMediaUrl(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (state.avatarUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPickAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
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

            SectionHeader(
                title = stringResource(R.string.settings_section_account),
                modifier = Modifier.padding(top = 24.dp),
            )
            SettingsRow(
                title = stringResource(R.string.profile_change_password),
                leadingIcon = Icons.Filled.Lock,
                onClick = onOpenChangePassword,
            )
            SettingsRow(
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(
                    languageOptionLabel(AppLocaleManager.current(LocalContext.current)),
                ),
                leadingIcon = Icons.Filled.Language,
                onClick = onOpenLanguage,
            )

            SectionHeader(
                title = stringResource(R.string.settings_section_appearance),
                modifier = Modifier.padding(top = 16.dp),
            )
            SettingsRow(
                title = stringResource(R.string.settings_theme_title),
                subtitle = ThemeMapper.resolveThemeLabel(
                    themeMode,
                    stringResource(R.string.theme_system),
                    stringResource(R.string.theme_light),
                    stringResource(R.string.theme_dark),
                ),
                leadingIcon = Icons.Filled.Palette,
                onClick = { showThemeDialog = true },
            )

            SectionHeader(
                title = stringResource(R.string.settings_section_privacy),
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTogglePresence(!showPresence) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.privacy_show_presence),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                Switch(
                    checked = showPresence,
                    onCheckedChange = onTogglePresence,
                    modifier = Modifier.semantics { role = Role.Switch },
                )
            }

            // AI Summary Consent Row (Phase 6)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleAiConsent(!aiConsent) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_ai_summary_consent_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_ai_summary_consent_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = aiConsent,
                    onCheckedChange = onToggleAiConsent,
                    modifier = Modifier.semantics { role = Role.Switch },
                )
            }

            SectionHeader(
                title = stringResource(R.string.settings_section_session),
                modifier = Modifier.padding(top = 16.dp),
            )
            SettingsRow(
                title = stringResource(R.string.action_logout),
                leadingIcon = Icons.AutoMirrored.Filled.Logout,
                onClick = onLogout,
                isDestructive = true,
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.action_logout_all),
                leadingIcon = Icons.Filled.Devices,
                onClick = { confirmLogoutAll = true },
                isDestructive = true,
                showChevron = false,
            )
        }
    }

    if (state.showPasswordDialog) {
        ChangePasswordDialog(
            state = state,
            onDismiss = onDismissPassword,
            onOldChange = onOldPasswordChange,
            onNewChange = onNewPasswordChange,
            onConfirmChange = onConfirmPasswordChange,
            onSubmit = onSubmitPassword,
        )
    }

    if (confirmLogoutAll) {
        AlertDialog(
            onDismissRequest = { confirmLogoutAll = false },
            title = { Text(stringResource(R.string.action_logout_all)) },
            text = { Text(stringResource(R.string.profile_logout_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogoutAll = false
                        onLogoutAll()
                    },
                ) {
                    Text(
                        stringResource(R.string.action_logout_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogoutAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentMode = themeMode,
            onSelect = onSelectTheme,
            onDismiss = { showThemeDialog = false },
        )
    }
}

@Composable
private fun ThemeSelectionDialog(
    currentMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_title)) },
        text = {
            Column {
                listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(mode)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (mode == currentMode),
                            onClick = {
                                onSelect(mode)
                                onDismiss()
                            },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ChangePasswordDialog(
    state: ProfileViewModel.UiState,
    onDismiss: () -> Unit,
    onOldChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_change_password)) },
        text = {
            Column {
                PasswordField(
                    value = state.oldPassword,
                    onValueChange = onOldChange,
                    labelRes = R.string.auth_current_password_label,
                    isError = state.oldPasswordError,
                    errorRes = R.string.error_current_password_required,
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = state.newPassword,
                    onValueChange = onNewChange,
                    labelRes = R.string.auth_new_password_label,
                    isError = state.newPasswordError,
                    errorRes = R.string.error_password_short,
                    modifier = Modifier.padding(top = 8.dp),
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = state.confirmPassword,
                    onValueChange = onConfirmChange,
                    labelRes = R.string.auth_confirm_password_label,
                    isError = state.confirmMismatch,
                    errorRes = R.string.error_password_mismatch,
                    modifier = Modifier.padding(top = 8.dp),
                    onImeAction = onSubmit,
                )
                state.passwordError?.let { error ->
                    Text(
                        text = apiErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.passwordSaving) {
                if (state.passwordSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.passwordSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
