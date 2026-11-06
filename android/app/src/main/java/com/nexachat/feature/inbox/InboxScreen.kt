package com.nexachat.feature.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import com.nexachat.call.ui.CallLogResourceResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import com.nexachat.core.ui.components.resolveRelativeTimeLabel
import com.nexachat.core.ui.theme.NexaShapes
import com.nexachat.data.ConversationSummary
import com.nexachat.feature.chat.chatErrorMessage
import com.nexachat.feature.contacts.InitialAvatar

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
    val justNowLabel = stringResource(R.string.time_just_now)
    val yesterdayLabel = stringResource(R.string.time_yesterday)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(onClick = onNavigateToContacts) {
                        Icon(
                            imageVector = Icons.Filled.People,
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
            FloatingActionButton(
                onClick = onNavigateToCreateGroup,
                shape = NexaShapes.fabShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
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
                    iconResId = R.drawable.ic_empty_chat,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    itemsIndexed(state.conversations, key = { _, item -> item.id }) { index, conversation ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                                ConversationRow(
                                    conversation = conversation,
                                    currentUserId = state.currentUserId,
                                    formatTime = { epochMs ->
                                        resolveRelativeTimeLabel(
                                            epochMs = epochMs,
                                            nowEpochMs = System.currentTimeMillis(),
                                            justNowLabel = justNowLabel,
                                            yesterdayLabel = yesterdayLabel,
                                        )
                                    },
                                    onClick = { onOpenConversation(conversation.id) },
                                )
                                if (index < state.conversations.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 72.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    currentUserId: String?,
    formatTime: (Long) -> String,
    onClick: () -> Unit,
) {
    val name = conversation.displayName.ifBlank { stringResource(R.string.chat_title) }
    val isUnread = conversation.unreadCount > 0
    val isDeleted = conversation.lastMessageDeleted
    val onlineDesc = stringResource(R.string.cd_online_status)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            InitialAvatar(name, conversation.displayAvatar, size = 48.dp)
            if (!conversation.isGroup && conversation.peerPresence?.online == true) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E))
                        .semantics { contentDescription = onlineDesc },
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = name,
                style = if (isUnread) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isDeleted) {
                Text(
                    text = stringResource(R.string.message_deleted),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val previewText = conversation.lastMessageContent.orEmpty().let { raw ->
                    if (raw.startsWith("CALL|")) {
                        val callLog = CallLogResourceResolver.fromSystemMessage(
                            raw,
                            CallPreviewDirection.isOutgoing(raw, currentUserId),
                        )
                        stringResource(callLog.resourceId, *callLog.arguments)
                    } else {
                        raw
                    }
                }
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                    color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            conversation.lastMessageAtEpochMs?.let { at ->
                Text(
                    text = formatTime(at),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isUnread) {
                Badge(
                    modifier = Modifier.padding(top = 4.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
