package com.nexachat.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import kotlinx.coroutines.flow.collectLatest

/**
 * Register: phone + displayName + password + confirm. The confirm check is
 * local UI logic (no reason to round-trip the ViewModel for a derived
 * comparison); everything else goes through [AuthViewModel].
 */
@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onRegistered: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var confirmMismatch by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                AuthViewModel.Event.LoggedIn -> onRegistered()
            }
        }
    }

    fun submit() {
        if (confirmPassword != state.password) {
            confirmMismatch = true
        } else {
            viewModel.register()
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = backgroundBrush)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.action_create_account),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
                label = { Text(stringResource(R.string.auth_display_name_label)) },
                singleLine = true,
                isError = state.displayNameError,
                supportingText = if (state.displayNameError) {
                    { Text(stringResource(R.string.error_display_name_required)) }
                } else {
                    null
                },
            )

            PhoneField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                isError = state.phoneError,
                modifier = Modifier.padding(top = 8.dp),
            )

            PasswordField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                labelRes = R.string.auth_password_label,
                isError = state.passwordError,
                errorRes = R.string.error_password_short,
                modifier = Modifier.padding(top = 8.dp),
                imeAction = ImeAction.Next,
            )

            PasswordField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    confirmMismatch = false
                },
                labelRes = R.string.auth_confirm_password_label,
                isError = confirmMismatch,
                errorRes = R.string.error_password_mismatch,
                modifier = Modifier.padding(top = 8.dp),
                imeAction = ImeAction.Done,
                onImeAction = ::submit,
            )

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text = apiErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            AuthSubmitButton(
                textRes = R.string.action_create_account,
                loading = state.loading,
                onClick = ::submit,
                modifier = Modifier.padding(top = 24.dp),
            )

            TextButton(
                onClick = onNavigateToLogin,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.action_sign_in))
            }
        }
    }
}
