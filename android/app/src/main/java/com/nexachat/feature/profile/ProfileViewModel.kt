package com.nexachat.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.AuthRepository
import com.nexachat.data.ContactsRepository
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
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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

    /** Clears the session locally and broadcasts LoggedOut — nav reacts globally. */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
