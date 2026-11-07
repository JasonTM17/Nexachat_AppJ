package com.nexachat.feature.profile

import android.net.Uri
import com.nexachat.core.common.Result
import com.nexachat.data.AuthRepository
import com.nexachat.data.ContactsRepository
import com.nexachat.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Suspend-only repository helpers for the Java ProfileViewModel. */
internal suspend fun profileLoad(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ProfileViewModel.UiState>,
) {
    uiState.update { it.withLoading(true).withError(null) }
    when (val result = repository.getMe()) {
        is Result.Success -> uiState.update {
            it.withLoading(false).withUser(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withLoading(false).withError(result.error)
        }
    }
}

internal suspend fun profileSaveDisplayName(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ProfileViewModel.UiState>,
    name: String,
) {
    uiState.update { it.withSaving(true).withSaveError(null) }
    when (val result = repository.updateDisplayName(name)) {
        is Result.Success -> uiState.update {
            it.withSaving(false).withEditingName(false).withUser(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withSaving(false).withSaveError(result.error)
        }
    }
}

internal suspend fun profileChangePassword(
    repository: AuthRepository,
    uiState: MutableStateFlow<ProfileViewModel.UiState>,
    oldPassword: String,
    newPassword: String,
) {
    uiState.update { it.withPasswordSaving(true).withPasswordError(null) }
    when (val result = repository.changePassword(oldPassword, newPassword)) {
        is Result.Success -> uiState.update {
            it.withPasswordSaving(false)
                .withShowPasswordDialog(false)
                .withPasswordChanged(true)
        }
        is Result.Failure -> uiState.update {
            it.withPasswordSaving(false).withPasswordError(result.error)
        }
    }
}

internal suspend fun profileUploadAvatar(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ProfileViewModel.UiState>,
    uri: Uri,
) {
    uiState.update { it.withAvatarUploading(true).withAvatarError(null) }
    when (val result = repository.uploadAvatar(uri)) {
        is Result.Success -> uiState.update {
            it.withAvatarUploading(false).withUser(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withAvatarUploading(false).withAvatarError(result.error)
        }
    }
}

internal suspend fun profileSetShowPresence(
    repository: SettingsRepository,
    uiState: MutableStateFlow<ProfileViewModel.UiState>,
    value: Boolean,
) {
    when (val result = repository.setShowPresence(value)) {
        is Result.Success -> uiState.update { it.withSaveError(null) }
        is Result.Failure -> uiState.update { it.withSaveError(result.error) }
    }
}

internal suspend fun profileLogout(repository: AuthRepository) {
    repository.logout()
}

internal suspend fun profileLogoutAll(repository: AuthRepository) {
    repository.logoutAll()
}
