package com.nexachat.feature.auth

import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.data.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.rx3.await

/** Suspend-only repository calls for the Java [AuthViewModel]. */
internal fun newAuthEventChannel(): Channel<AuthViewModel.Event> =
    Channel(Channel.BUFFERED)

internal fun authEventsFlow(channel: Channel<AuthViewModel.Event>): Flow<AuthViewModel.Event> =
    channel.receiveAsFlow()

internal fun authReadOnlyUiState(
    uiState: MutableStateFlow<AuthViewModel.UiState>,
): StateFlow<AuthViewModel.UiState> = uiState.asStateFlow()

internal suspend fun authSubmitLogin(
    authRepository: AuthRepository,
    phone: String,
    password: String,
    uiState: MutableStateFlow<AuthViewModel.UiState>,
    events: Channel<AuthViewModel.Event>,
) {
    authSubmit(uiState, events) { authRepository.login(phone, password).await() }
}

internal suspend fun authSubmitRegister(
    authRepository: AuthRepository,
    phone: String,
    password: String,
    displayName: String,
    uiState: MutableStateFlow<AuthViewModel.UiState>,
    events: Channel<AuthViewModel.Event>,
) {
    authSubmit(uiState, events) {
        authRepository.register(phone, password, displayName).await()
    }
}

private suspend fun authSubmit(
    uiState: MutableStateFlow<AuthViewModel.UiState>,
    events: Channel<AuthViewModel.Event>,
    call: suspend () -> Result<AuthResponse>,
) {
    uiState.update { it.withLoading(true, null) }
    when (val result = call()) {
        is Result.Success -> {
            uiState.update { it.withLoading(false, null) }
            events.send(AuthViewModel.Event.LoggedIn)
        }
        is Result.Failure -> uiState.update { it.withLoading(false, result.error) }
    }
}
