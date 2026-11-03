package com.nexachat.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.SessionEventsEntryPoint
import com.nexachat.feature.auth.LoginScreen
import com.nexachat.feature.auth.RegisterScreen
import com.nexachat.feature.inbox.InboxScreen
import com.nexachat.feature.splash.SplashScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collectLatest

/**
 * Central route table. Feature packages must not hardcode route strings —
 * add destinations here (or expose them via a per-feature navigation contract).
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val INBOX = "inbox"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Global session-dead signal (refresh rejected / logout): drop everything
    // and land on Login regardless of which screen is on top.
    val sessionEvents = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SessionEventsEntryPoint::class.java,
        ).sessionEvents()
    }
    LaunchedEffect(sessionEvents) {
        sessionEvents.events.collectLatest { event ->
            when (event) {
                SessionEvents.Event.LoggedOut -> navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onNavigateToLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onNavigateToInbox = {
                    navController.navigate(Routes.INBOX) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
                onLoggedIn = {
                    navController.navigate(Routes.INBOX) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = { navController.popBackStack() },
                onRegistered = {
                    navController.navigate(Routes.INBOX) {
                        // LOGIN sits under REGISTER in the back stack; popping it
                        // inclusively removes both auth screens.
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.INBOX) {
            InboxScreen()
        }
    }
}
