package com.nexachat.feature.inbox

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import com.nexachat.data.ConversationSummary
import com.nexachat.feature.chat.chatErrorMessage
import com.nexachat.feature.contacts.InitialAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation inbox (plan §12.1 feature/inbox): rows render the Room stream —
 * avatar letter, resolved name, last-message snippet, unread badge, time.
 * Row taps route to `chat/{id}` via [onOpenConversation]; top-bar actions keep
 * their existing contacts/profile routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onNavigateToContacts: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCreateGroup: () -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(onClick = onNavigateToContacts) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = stringResource(R.string.contacts_title),
                        )
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = stringResource(R.string.profile_title),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateGroup) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_create_group),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading && state.conversations.isEmpty() -> LoadingState()
                state.error != null && state.conversations.isEmpty() -> ErrorState(
                    message = chatErrorMessage(state.error!!),
                    onRetry = viewModel::refresh,
                )
                state.conversations.isEmpty() -> EmptyState(
                    message = stringResource(R.string.inbox_empty),
                    icon = Icons.Outlined.Forum,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            formatTime = { epochMs -> formatInboxTime(epochMs, timeFormat, dateFormat) },
                            onClick = { onOpenConversation(conversation.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    formatTime: (Long) -> String,
    onClick: () -> Unit,
) {
    val name = conversation.displayName.ifBlank { stringResource(R.string.chat_title) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(name, conversation.displayAvatar)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.lastMessageContent.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            conversation.lastMessageAtEpochMs?.let { at ->
                Text(
                    text = formatTime(at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (conversation.unreadCount > 0) {
                Badge(modifier = Modifier.padding(top = 4.dp)) {
                    Text(conversation.unreadCount.toString())
                }
            }
        }
    }
}

/** HH:mm for same-day messages, dd/MM for older — no year needed for an inbox. */
private fun formatInboxTime(
    epochMs: Long,
    timeFormat: SimpleDateFormat,
    dateFormat: SimpleDateFormat,
): String =
    if (DateUtils.isToday(epochMs)) {
        timeFormat.format(Date(epochMs))
    } else {
        dateFormat.format(Date(epochMs))
    }
