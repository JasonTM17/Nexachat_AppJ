package com.nexachat.feature.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.AuthRepository
import com.nexachat.data.ContactsRepository
import com.nexachat.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Profile screen state: own user record (`GET /users/me`), displayName edit
 * (`PATCH /users/me`), and logout. Logout goes through
 * [AuthRepository.logout] → `SessionEvents.LoggedOut` → the AppNavHost-level
 * collector drops the whole back stack to Login — no event plumbing needed here.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val user: UserDto? = null,
        val error: ApiError? = null,
        // Display-name edit row.
        val editingName: Boolean = false,
        val nameInput: String = "",
        val saving: Boolean = false,
        val saveError: ApiError? = null,
        // Change-password dialog (PATCH /auth/password, FR-A6).
        val showPasswordDialog: Boolean = false,
        val oldPassword: String = "",
        val newPassword: String = "",
        val confirmPassword: String = "",
        val oldPasswordError: Boolean = false,
        val newPasswordError: Boolean = false,
        val confirmMismatch: Boolean = false,
        val passwordSaving: Boolean = false,
        val passwordError: ApiError? = null,
        val passwordChanged: Boolean = false,
        // Avatar upload row (POST /users/me/avatar, FR-A5).
        val avatarUploading: Boolean = false,
        val avatarError: ApiError? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Presence privacy toggle (F3) — server-backed, optimistic in the repo. */
    val showPresence: StateFlow<Boolean> = settingsRepository.showPresence

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = contactsRepository.getMe()) {
                is Result.Success -> _uiState.update {
                    it.copy(loading = false, user = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    fun startEditName() =
        _uiState.update {
            it.copy(
                editingName = true,
                nameInput = it.user?.displayName.orEmpty(),
                saveError = null,
            )
        }

    fun onNameInputChange(value: String) =
        _uiState.update { it.copy(nameInput = value, saveError = null) }

    fun cancelEditName() =
        _uiState.update { it.copy(editingName = false, nameInput = "", saveError = null) }

    fun saveDisplayName() {
        val state = _uiState.value
        val name = state.nameInput.trim()
        if (name.isEmpty() || state.saving) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            when (val result = contactsRepository.updateDisplayName(name)) {
                is Result.Success -> _uiState.update {
                    it.copy(saving = false, editingName = false, user = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(saving = false, saveError = result.error)
                }
            }
        }
    }

    fun openPasswordDialog() =
        _uiState.update {
            it.copy(
                showPasswordDialog = true,
                oldPassword = "",
                newPassword = "",
                confirmPassword = "",
                oldPasswordError = false,
                newPasswordError = false,
                confirmMismatch = false,
                passwordError = null,
                passwordChanged = false,
            )
        }

    fun dismissPasswordDialog() =
        _uiState.update { it.copy(showPasswordDialog = false, passwordError = null) }

    fun onOldPasswordChange(value: String) =
        _uiState.update { it.copy(oldPassword = value, oldPasswordError = false, passwordError = null) }

    fun onNewPasswordChange(value: String) =
        _uiState.update { it.copy(newPassword = value, newPasswordError = false, passwordError = null) }

    fun onConfirmPasswordChange(value: String) =
        _uiState.update { it.copy(confirmPassword = value, confirmMismatch = false, passwordError = null) }

    fun consumePasswordChanged() =
        _uiState.update { it.copy(passwordChanged = false) }

    fun submitPasswordChange() {
        val state = _uiState.value
        if (state.passwordSaving) return
        val oldBad = state.oldPassword.isEmpty()
        val newBad = state.newPassword.length < 8
        val mismatch = state.newPassword != state.confirmPassword
        if (oldBad || newBad || mismatch) {
            _uiState.update {
                it.copy(
                    oldPasswordError = oldBad,
                    newPasswordError = newBad,
                    confirmMismatch = mismatch,
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(passwordSaving = true, passwordError = null) }
            when (val result = authRepository.changePassword(state.oldPassword, state.newPassword)) {
                is Result.Success -> _uiState.update {
                    it.copy(passwordSaving = false, showPasswordDialog = false, passwordChanged = true)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(passwordSaving = false, passwordError = result.error)
                }
            }
        }
    }

    /**
     * Avatar pick → `POST /users/me/avatar` → refreshed `user` (FR-A5).
     * The repo returns the re-fetched record so `avatarUrl` in state is always
     * the server-persisted value, not an optimistic guess.
     */
    fun uploadAvatar(uri: Uri) {
        if (_uiState.value.avatarUploading) return
        viewModelScope.launch {
            _uiState.update { it.copy(avatarUploading = true, avatarError = null) }
            when (val result = contactsRepository.uploadAvatar(uri)) {
                is Result.Success -> _uiState.update {
                    it.copy(avatarUploading = false, user = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(avatarUploading = false, avatarError = result.error)
                }
            }
        }
    }

    fun consumeAvatarError() = _uiState.update { it.copy(avatarError = null) }

    /**
     * Privacy switch (F3). The repository flips the value optimistically and
     * reverts on failure; the error lands in the same `saveError` slot the
     * profile name edit uses.
     */
    fun setShowPresence(value: Boolean) {
        viewModelScope.launch {
            when (val result = settingsRepository.setShowPresence(value)) {
                is Result.Success -> _uiState.update { it.copy(saveError = null) }
                is Result.Failure -> _uiState.update { it.copy(saveError = result.error) }
            }
        }
    }

    /** Clears the session locally and broadcasts LoggedOut — nav reacts globally. */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    /** FR-A4 logout-all: revokes every refresh token, then same local clear. */
    fun logoutAll() {
        viewModelScope.launch { authRepository.logoutAll() }
    }
}
