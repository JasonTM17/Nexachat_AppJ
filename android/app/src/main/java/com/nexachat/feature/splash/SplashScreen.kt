package com.nexachat.feature.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexachat.R
import com.nexachat.feature.auth.SplashViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Session restore: renders branding + progress while [SplashViewModel] checks
 * for a stored refresh token and attempts rotation, then emits exactly one nav
 * event → login or inbox (phase-05 M1).
 */
@Composable
fun SplashScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToInbox: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.navEvents.collectLatest { event ->
            when (event) {
                SplashViewModel.NavEvent.ToLogin -> onNavigateToLogin()
                SplashViewModel.NavEvent.ToInbox -> onNavigateToInbox()
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
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.splash_tagline),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        CircularProgressIndicator(
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
