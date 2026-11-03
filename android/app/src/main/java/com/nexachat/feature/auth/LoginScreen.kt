package com.nexachat.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import kotlinx.coroutines.flow.collectLatest

/**
 * Phone + password sign-in (plan Module A). One-shot [AuthViewModel.Event]s
 * drive navigation; everything else renders from [AuthViewModel.UiState].
 */
@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoggedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                AuthViewModel.Event.LoggedIn -> onLoggedIn()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        PhoneField(
            value = state.phone,
            onValueChange = viewModel::onPhoneChange,
            isError = state.phoneError,
            modifier = Modifier.padding(top = 32.dp),
        )

        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            labelRes = R.string.auth_password_label,
            isError = state.passwordError,
            errorRes = R.string.error_password_short,
            modifier = Modifier.padding(top = 8.dp),
            onImeAction = viewModel::login,
        )

        state.error?.let { error ->
            Text(
                text = apiErrorMessage(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = 8.dp),
            )
        }

        AuthSubmitButton(
            textRes = R.string.action_sign_in,
            loading = state.loading,
            onClick = viewModel::login,
            modifier = Modifier.padding(top = 24.dp),
        )

        TextButton(
            onClick = onNavigateToRegister,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.action_create_account))
        }
    }
}
