package com.nexachat.core.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexachat.feature.inbox.InboxScreen
import com.nexachat.feature.splash.SplashScreen

/**
 * Central route table. Feature packages must not hardcode route strings —
 * add destinations here (or expose them via a per-feature navigation contract).
 */
object Routes {
    const val SPLASH = "splash"
    const val INBOX = "inbox"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.INBOX) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.INBOX) {
            InboxScreen()
        }
    }
}
