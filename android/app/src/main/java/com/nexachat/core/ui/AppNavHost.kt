package com.nexachat.core.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.SessionEventsEntryPoint
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.feature.auth.LoginScreen
import com.nexachat.feature.auth.RegisterScreen
import com.nexachat.feature.chat.ChatScreen
import com.nexachat.feature.contacts.ContactsScreen
import com.nexachat.feature.contacts.UserSearchScreen
import com.nexachat.feature.group.CreateGroupScreen
import com.nexachat.feature.group.GroupDetailScreen
import com.nexachat.feature.inbox.InboxScreen
import com.nexachat.feature.media.MediaViewerScreen
import com.nexachat.feature.profile.LanguageSettingsScreen
import com.nexachat.feature.profile.ProfileScreen
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
    const val CONTACTS = "contacts"
    const val SEARCH = "search"
    const val PROFILE = "profile"
    const val LANGUAGE_SETTINGS = "settings/language"
    const val CHAT = "chat/{conversationId}"
    const val GROUP_CREATE = "group/create"
    const val GROUP_DETAIL = "group/{conversationId}/detail"
    const val MEDIA_VIEWER = "media/{attachmentId}?name={name}&mime={mime}"

    fun chat(conversationId: String): String = "chat/$conversationId"
    fun groupDetail(conversationId: String): String = "group/$conversationId/detail"

    /** Viewer args ride the query string — names/mimes can carry `/` and spaces. */
    fun mediaViewer(attachment: AttachmentDto): String =
        "media/${attachment.id}" +
            "?name=${Uri.encode(attachment.originalName.orEmpty())}" +
            "&mime=${Uri.encode(attachment.mimeType.orEmpty())}"
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
                is SessionEvents.Event.LoggedOut -> navController.navigate(Routes.LOGIN) {
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
            InboxScreen(
                onNavigateToContacts = { navController.navigate(Routes.CONTACTS) },
                onNavigateToProfile = { navController.navigate(Routes.PROFILE) },
                onNavigateToCreateGroup = { navController.navigate(Routes.GROUP_CREATE) },
                onOpenConversation = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
            )
        }
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToSearch = { navController.navigate(Routes.SEARCH) },
                onOpenConversation = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
            )
        }
        composable(Routes.SEARCH) {
            UserSearchScreen(
                onBack = { navController.popBackStack() },
                onOpenConversation = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onOpenLanguage = { navController.navigate(Routes.LANGUAGE_SETTINGS) },
            )
        }
        composable(Routes.LANGUAGE_SETTINGS) {
            LanguageSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { entry ->
            val currentConversationId = entry.arguments?.getString("conversationId").orEmpty()
            ChatScreen(
                conversationId = currentConversationId,
                onBack = { navController.popBackStack() },
                onOpenDetail = { conversationId ->
                    navController.navigate(Routes.groupDetail(conversationId))
                },
                onOpenMedia = { attachment ->
                    navController.navigate(Routes.mediaViewer(attachment))
                },
                onOpenConversation = { targetId ->
                    if (targetId.isNotBlank() && targetId != currentConversationId) {
                        navController.navigate(Routes.chat(targetId))
                    }
                },
            )
        }
        composable(
            route = Routes.MEDIA_VIEWER,
            arguments = listOf(
                navArgument("attachmentId") { type = NavType.StringType },
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("mime") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            MediaViewerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GROUP_CREATE) {
            CreateGroupScreen(
                onBack = { navController.popBackStack() },
                onCreated = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        // Replace the create form — Back from the new group chat
                        // returns to the inbox, not to a stale form.
                        popUpTo(Routes.GROUP_CREATE) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.GROUP_DETAIL,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            GroupDetailScreen(
                onBack = { navController.popBackStack() },
                onExitToInbox = {
                    // Leaving drops the whole chat branch — the user must not
                    // land back on a conversation they are no longer in.
                    navController.popBackStack(Routes.INBOX, inclusive = false)
                },
            )
        }
    }
}
