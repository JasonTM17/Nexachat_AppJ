package com.nexachat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared ViewModel for the Login and Register destinations (each nav entry
 * gets its own instance via `hiltViewModel()` — no state leaks between them).
 *
 * Field inputs live in [UiState] so they survive config changes; one-shot
 * navigation uses a [Channel] (plan §12.2 — never SingleLiveEvent).
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val phone: String = "",
        val password: String = "",
        val displayName: String = "",
        val loading: Boolean = false,
        /** Server/network error shown above the submit button. */
        val error: ApiError? = null,
        // Client-side field validation flags (mapped to strings in the screen).
        val phoneError: Boolean = false,
        val passwordError: Boolean = false,
        val displayNameError: Boolean = false,
    )

    sealed interface Event {
        data object LoggedIn : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onPhoneChange(value: String) =
        _uiState.update { it.copy(phone = value, phoneError = false) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, passwordError = false) }

    fun onDisplayNameChange(value: String) =
        _uiState.update { it.copy(displayName = value, displayNameError = false) }

    fun login() {
        val state = _uiState.value
        val phoneInvalid = state.phone.isBlank()
        val passwordInvalid = state.password.length < MIN_PASSWORD_LENGTH
        if (phoneInvalid || passwordInvalid) {
            _uiState.update {
                it.copy(phoneError = phoneInvalid, passwordError = passwordInvalid, error = null)
            }
            return
        }
        submit { authRepository.login(state.phone, state.password) }
    }

    fun register() {
        val state = _uiState.value
        val phoneInvalid = state.phone.isBlank()
        val passwordInvalid = state.password.length < MIN_PASSWORD_LENGTH
        val nameInvalid = state.displayName.isBlank()
        if (phoneInvalid || passwordInvalid || nameInvalid) {
            _uiState.update {
                it.copy(
                    phoneError = phoneInvalid,
                    passwordError = passwordInvalid,
                    displayNameError = nameInvalid,
                    error = null,
                )
            }
            return
        }
        submit { authRepository.register(state.phone, state.password, state.displayName) }
    }

    private fun submit(call: suspend () -> Result<AuthResponse>) {
        if (_uiState.value.loading) return // double-tap / IME re-submit guard
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = call()) {
                is Result.Success -> {
                    _uiState.update { it.copy(loading = false) }
                    _events.send(Event.LoggedIn)
                }
                is Result.Failure ->
                    _uiState.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
