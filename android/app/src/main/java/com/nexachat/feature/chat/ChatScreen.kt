package com.nexachat.feature.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.call.CallLauncher
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.ui.ForwardFilter
import com.nexachat.core.ui.ForwardPickerRules
import com.nexachat.core.ui.MessageResolver
import com.nexachat.core.ui.MessageUiRules
import com.nexachat.core.ui.PresenceFormatter
import com.nexachat.core.ui.PresenceTickerHelper
import com.nexachat.core.ui.SearchHighlightHelper
import com.nexachat.core.ui.SearchUiRules
import com.nexachat.core.ui.components.ChatBubble
import com.nexachat.core.ui.components.DateSeparator
import com.nexachat.core.ui.components.DateSeparatorLabels
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.MessageGroupPosition
import com.nexachat.core.ui.components.resolveDateSeparatorLabel
import com.nexachat.core.ui.components.resolveGroupPosition
import com.nexachat.core.ui.components.shouldShowDateSeparator
import com.nexachat.core.ui.theme.NexaShapes
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationSummary
import com.nexachat.data.DeliveryStatus
import com.nexachat.feature.contacts.InitialAvatar
import com.nexachat.feature.inbox.InboxViewModel
import com.nexachat.feature.inbox.InboxUiState
import com.nexachat.feature.media.MediaPickerSheet
import com.nexachat.feature.media.MessageAttachmentContent
import com.nexachat.feature.media.PendingAttachmentStrip
import com.nexachat.feature.media.rememberAuthedImageLoader
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.nexachat.core.ui.SummaryUiRules
import com.nexachat.data.ConversationRepository
import com.nexachat.feature.summary.SummaryBottomSheet
import com.nexachat.feature.summary.UnreadBanner
import com.nexachat.summary.SummaryScope
import com.nexachat.summary.SummaryViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lets ChatScreen reach the ConversationRepository singleton for unread counts. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatScreenEntryPoint {
    fun conversationRepository(): ConversationRepository
}

/**
 * Direct and group chat (plan §12.1, FR-C1..C7 + stage 2 F1-F5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit = {},
    onOpenMedia: (AttachmentDto) -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    summaryViewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val imageLoader = rememberAuthedImageLoader()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showMediaPicker by rememberSaveable { mutableStateOf(false) }
    var pinIndex by rememberSaveable { mutableIntStateOf(0) }
    var jumpingTargetId by remember { mutableStateOf<String?>(null) }
    var pulsingMessageId by remember { mutableStateOf<String?>(null) }

    // Phase 6 Summary states (rotation-safe primitives only):
    var showSummarySheet by rememberSaveable { mutableStateOf(false) }
    var dismissedBannerSeq by rememberSaveable(conversationId) { mutableStateOf<Long?>(null) }

    // Presence clock ticker for offline peer relative time display (codex-droid request)
    var presenceNowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shouldTickPresence = PresenceTickerHelper.shouldTick(
        state.isGroup,
        state.connected,
        state.peerTyping,
        state.peerPresence?.online,
        state.peerPresence?.lastSeenAtEpochMs,
    )
    LaunchedEffect(shouldTickPresence, state.peerPresence?.lastSeenAtEpochMs) {
        if (!shouldTickPresence) return@LaunchedEffect
        val lastSeen = state.peerPresence?.lastSeenAtEpochMs ?: return@LaunchedEffect
        presenceNowEpochMs = System.currentTimeMillis()
        while (isActive) {
            val delayMs = PresenceTickerHelper.computeNextDelayMs(System.currentTimeMillis(), lastSeen)
            delay(delayMs)
            presenceNowEpochMs = System.currentTimeMillis()
        }
    }

    val convRepo = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChatScreenEntryPoint::class.java,
        ).conversationRepository()
    }
    val inboxConversations by convRepo.inboxFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val thisConversation = remember(inboxConversations, conversationId) {
        inboxConversations.firstOrNull { it.id == conversationId }
    }
    var bannerUnreadCount by rememberSaveable(conversationId) { mutableIntStateOf(0) }
    val incomingCount = remember(state.messages) { state.messages.count { !it.mine } }
    LaunchedEffect(thisConversation?.unreadCount, incomingCount) {
        val currentDb = thisConversation?.unreadCount ?: 0
        if (currentDb >= 20 && bannerUnreadCount < currentDb) {
            bannerUnreadCount = currentDb
        } else if (bannerUnreadCount == 0 && incomingCount >= 20 && dismissedBannerSeq == null) {
            bannerUnreadCount = incomingCount
        }
    }
    val unreadCount = if (bannerUnreadCount > 0) bannerUnreadCount else (thisConversation?.unreadCount ?: 0)

    // Stage 2 & 3 States (D1, D5: saveable IDs to survive rotation cleanly):
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }

    var actionSheetMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    val actionSheetMessage = remember(state.messages, actionSheetMessageId) {
        MessageResolver.resolveForActionSheet(state.messages, actionSheetMessageId)
    }

    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingMessage = remember(state.messages, editingMessageId) {
        MessageResolver.resolveForEdit(state.messages, editingMessageId)
    }
    var editInput by rememberSaveable { mutableStateOf("") }

    var messageToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val messageToDelete = remember(state.messages, messageToDeleteId) {
        MessageResolver.findById(state.messages, messageToDeleteId)
    }

    var forwardingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    val forwardingMessage = remember(state.messages, forwardingMessageId) {
        MessageResolver.findById(state.messages, forwardingMessageId)
    }

    // Gracefully clear editing mode or action sheet if target message disappears after rotation
    LaunchedEffect(state.messages, editingMessageId) {
        if (editingMessageId != null && state.messages.isNotEmpty() && editingMessage == null) {
            editingMessageId = null
            editInput = ""
        }
    }
    LaunchedEffect(state.messages, actionSheetMessageId) {
        if (actionSheetMessageId != null && state.messages.isNotEmpty() && actionSheetMessage == null) {
            actionSheetMessageId = null
        }
    }

    // Clear pulse after 1.2s
    LaunchedEffect(pulsingMessageId) {
        if (pulsingMessageId != null) {
            delay(1200L)
            pulsingMessageId = null
        }
    }

    // Jump handling
    LaunchedEffect(viewModel) {
        viewModel.jumpTargets.collect { targetId ->
            jumpingTargetId = null
            pulsingMessageId = targetId
            var index = findMessageReversedIndex(state.messages, targetId)
            if (index < 0) {
                snapshotFlow { state.messages }
                    .filter { msgs -> msgs.any { it.id == targetId } }
                    .first()
                index = findMessageReversedIndex(state.messages, targetId)
            }
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    // Action events feedback (Forward)
    LaunchedEffect(viewModel) {
        viewModel.actionEvents.collect { event ->
            when (event) {
                is ChatActionEvent.MessageEdited -> {
                    if (editingMessageId == event.messageId) {
                        editingMessageId = null
                        editInput = ""
                    }
                }
                is ChatActionEvent.MessageForwarded -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.forwarded_snackbar),
                        actionLabel = context.getString(R.string.action_view),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onOpenConversation(event.targetConversationId)
                    }
                }
            }
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            jumpingTargetId = null
        }
    }

    val newest = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (newest?.mine == true || listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    val visualPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_VISUAL_PICK),
    ) { uris -> viewModel.sendAttachments(uris) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.sendAttachments(uris) }

    fun openFile(attachment: AttachmentDto) {
        scope.launch {
            val file = viewModel.attachmentFile(attachment)
            if (file == null) {
                Toast.makeText(context, R.string.error_attachment_open, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, attachment.mimeType ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.error_attachment_open, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showMediaPicker) {
        MediaPickerSheet(
            onPickVisual = { visualPicker.launch(PICK_VISUAL_REQUEST) },
            onPickDocument = { documentPicker.launch(arrayOf("*/*")) },
            onDismiss = { showMediaPicker = false },
        )
    }

    // Delete message confirmation dialog
    if (messageToDelete != null) {
        val target = messageToDelete
        AlertDialog(
            onDismissRequest = { messageToDeleteId = null },
            title = { Text(stringResource(R.string.dialog_delete_message_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMessage(target.id)
                        messageToDeleteId = null
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Message action bottom sheet (D5, font scale scrollable)
    if (actionSheetMessage != null) {
        val msg = actionSheetMessage
        val isMine = msg.mine
        val isDeleted = MessageUiRules.isTombstone(msg.isDeleted, msg.deletedAtEpochMs)
        val replyable = isMessageReplyable(msg.status) && !isDeleted
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { actionSheetMessageId = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                if (replyable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        viewModel.toggleReaction(msg, emoji)
                                        actionSheetMessageId = null
                                    }
                                    .wrapContentSize()
                                    .padding(8.dp),
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                // Reply
                if (replyable) {
                    ActionSheetRow(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        title = stringResource(R.string.action_reply),
                        onClick = {
                            viewModel.setReplyTarget(msg)
                            actionSheetMessageId = null
                        },
                    )
                }

                // Copy
                if (MessageUiRules.canCopy(msg.type, isDeleted)) {
                    ActionSheetRow(
                        icon = Icons.Filled.ContentCopy,
                        title = stringResource(R.string.action_copy),
                        onClick = {
                            clipboardManager.setText(AnnotatedString(msg.content.orEmpty()))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionSheetMessageId = null
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.copied_to_clipboard))
                            }
                        },
                    )
                }

                // Forward
                if (ForwardFilter.canForward(msg.type, isDeleted)) {
                    ActionSheetRow(
                        icon = Icons.AutoMirrored.Filled.Forward,
                        title = stringResource(R.string.action_forward),
                        onClick = {
                            forwardingMessageId = msg.id
                            actionSheetMessageId = null
                        },
                    )
                }

                // Pin / Unpin
                if (state.canPin && replyable) {
                    val isPinned = state.isPinned(msg.id)
                    ActionSheetRow(
                        icon = Icons.Filled.PushPin,
                        title = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
                        onClick = {
                            viewModel.togglePin(msg)
                            actionSheetMessageId = null
                        },
                    )
                }

                // Edit (Mine, TEXT, non-deleted)
                if (MessageUiRules.canEdit(isMine, msg.type, isDeleted)) {
                    ActionSheetRow(
                        icon = Icons.Filled.Edit,
                        title = stringResource(R.string.action_edit),
                        onClick = {
                            editingMessageId = msg.id
                            editInput = msg.content.orEmpty()
                            actionSheetMessageId = null
                        },
                    )
                }

                // Delete (Mine, non-deleted)
                if (MessageUiRules.canDelete(isMine, isDeleted)) {
                    ActionSheetRow(
                        icon = Icons.Filled.Delete,
                        title = stringResource(R.string.action_delete),
                        isDestructive = true,
                        onClick = {
                            actionSheetMessageId = null
                            messageToDeleteId = msg.id
                        },
                    )
                }
            }
        }
    }

    // Forward conversation picker sheet
    if (forwardingMessage != null) {
        val targetMsg = forwardingMessage
        ForwardConversationSheet(
            onDismiss = { forwardingMessageId = null },
            onSelectConversation = { targetConvId ->
                viewModel.forwardMessage(targetMsg.id, targetConvId)
                forwardingMessageId = null
            },
        )
    }

    // Summary Bottom Sheet (Phase 6)
    if (showSummarySheet) {
        val messageSeqMap = remember(state.messages) {
            state.messages.associate { it.id to it.seq }
        }
        val localMessageIds = remember(state.messages) {
            state.messages.map { it.id }.toSet()
        }

        SummaryBottomSheet(
            viewModel = summaryViewModel,
            messageSeqMap = messageSeqMap,
            localMessageIds = localMessageIds,
            onDismiss = { showSummarySheet = false },
            onJumpToMessage = { targetId ->
                showSummarySheet = false
                jumpingTargetId = targetId
                pulsingMessageId = targetId
                viewModel.jumpToMessage(targetId)
            },
            onShareIntoChat = { shareText ->
                showSummarySheet = false
                viewModel.onInputChange(shareText)
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSearchOpen) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchState.query,
                            onValueChange = viewModel::search,
                            placeholder = { Text(stringResource(R.string.search_in_chat_placeholder)) },
                            singleLine = true,
                            shape = NexaShapes.searchBarPill,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (searchState.query.isNotEmpty()) {
                                    IconButton(onClick = viewModel::clearSearch) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.cd_clear_search),
                                        )
                                    }
                                }
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                isSearchOpen = false
                                viewModel.clearSearch()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                InitialAvatar(
                                    name = state.peerName ?: stringResource(R.string.chat_title),
                                    size = 38.dp,
                                )
                                if (!state.isGroup && state.peerPresence?.online == true) {
                                    Box(
                                        modifier = Modifier
                                            .size(11.dp)
                                            .align(Alignment.BottomEnd)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(1.5.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF22C55E))
                                            .semantics {
                                                contentDescription = context.getString(R.string.cd_online_status)
                                            },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = state.peerName ?: stringResource(R.string.chat_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitleText = when {
                                    state.peerTyping -> stringResource(R.string.chat_typing)
                                    !state.connected -> stringResource(R.string.chat_offline)
                                    !state.isGroup && state.peerPresence != null -> {
                                        PresenceFormatter.formatPresence(
                                            state.peerPresence!!.online,
                                            state.peerPresence!!.lastSeenAtEpochMs,
                                            presenceNowEpochMs,
                                            stringResource(R.string.presence_active_now),
                                            stringResource(R.string.presence_minutes_ago),
                                            stringResource(R.string.presence_hours_ago),
                                            stringResource(R.string.presence_yesterday),
                                            "dd/MM",
                                            Locale.getDefault(),
                                        )
                                    }
                                    else -> null
                                }
                                if (subtitleText != null) {
                                    Text(
                                        text = subtitleText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (state.peerTyping) {
                                            MaterialTheme.colorScheme.primary
                                        } else if (!state.connected) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                    actions = {
                        var showOverflowMenu by remember { mutableStateOf(false) }

                        if (!state.isGroup) {
                            val peerUserId = state.otherUserId
                                ?: state.messages.firstOrNull { !it.mine }?.senderId.orEmpty()
                            IconButton(
                                onClick = {
                                    CallLauncher.start(
                                        context,
                                        conversationId,
                                        peerUserId,
                                        state.peerName.orEmpty(),
                                    )
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Call,
                                    contentDescription = stringResource(R.string.cd_call),
                                )
                            }
                            IconButton(onClick = { isSearchOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.cd_search_in_chat),
                                )
                            }
                        } else {
                            IconButton(onClick = { isSearchOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.cd_search_in_chat),
                                )
                            }
                            IconButton(onClick = { onOpenDetail(conversationId) }) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = stringResource(R.string.cd_group_info),
                                )
                            }
                        }

                        // Overflow menu for both DIRECT and GROUP: "Tóm tắt cuộc trò chuyện"
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.cd_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.summary_overflow_action)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSummarySheet = true
                                        summaryViewModel.request(SummaryScope.UNREAD, null)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { innerPadding ->
        if (isSearchOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                SearchOverlay(
                    searchState = searchState,
                    timeFormat = timeFormat,
                    onSelectResult = { resultMessage ->
                        isSearchOpen = false
                        viewModel.clearSearch()
                        viewModel.jumpToMessage(resultMessage.id)
                        pulsingMessageId = resultMessage.id
                    },
                    onLoadMore = viewModel::loadMoreSearch,
                    onRetry = { viewModel.search(searchState.query) },
                )
            }
        } else {
            val todayStr = stringResource(R.string.time_today)
            val yesterdayStr = stringResource(R.string.time_yesterday)
            val dateLabels = remember(todayStr, yesterdayStr) {
                DateSeparatorLabels(todayStr, yesterdayStr)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 640.dp)
                        .fillMaxWidth(),
                ) {
                    val currentLastSeq = state.messages.lastOrNull()?.seq ?: 0L
                    if (SummaryUiRules.shouldShowBanner(unreadCount, dismissedBannerSeq, currentLastSeq)) {
                        UnreadBanner(
                            unreadCount = unreadCount,
                            onSummarize = {
                                showSummarySheet = true
                                summaryViewModel.request(SummaryScope.UNREAD, null)
                            },
                            onDismiss = {
                                dismissedBannerSeq = currentLastSeq
                                bannerUnreadCount = 0
                            },
                        )
                    }

                    if (jumpingTargetId != null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.pins.isNotEmpty()) {
                        PinnedBanner(
                            pins = state.pins,
                            index = pinIndex,
                            onJump = {
                                state.pins.getOrNull(pinIndex % state.pins.size)?.let { pin ->
                                    if (findMessageReversedIndex(state.messages, pin.id) < 0) {
                                        jumpingTargetId = pin.id
                                    }
                                    viewModel.jumpToMessage(pin.id)
                                }
                                pinIndex++
                            },
                        )
                    }
                    if (!state.connected) {
                        Text(
                            text = stringResource(R.string.chat_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = state.messages.asReversed(),
                                key = { it.id },
                            ) { message ->
                                val origIdx = remember(state.messages, message.id) {
                                    state.messages.indexOfFirst { it.id == message.id }
                                }
                                val groupPosition = remember(state.messages, origIdx) {
                                    resolveGroupPosition(state.messages, origIdx)
                                }
                                val prevMsg = remember(state.messages, origIdx) {
                                    state.messages.getOrNull(origIdx - 1)
                                }
                                val showDateSeparator = remember(prevMsg?.createdAtEpochMs, message.createdAtEpochMs) {
                                    shouldShowDateSeparator(prevMsg?.createdAtEpochMs, message.createdAtEpochMs)
                                }
                                val dateLabel = if (showDateSeparator) {
                                    resolveDateSeparatorLabel(
                                        messageEpochMs = message.createdAtEpochMs,
                                        nowEpochMs = System.currentTimeMillis(),
                                        labels = dateLabels,
                                    )
                                } else null

                                MessageItem(
                                    message = message,
                                    showSender = state.isGroup && !message.mine &&
                                        (groupPosition == MessageGroupPosition.FIRST || groupPosition == MessageGroupPosition.SINGLE),
                                    groupPosition = groupPosition,
                                    dateSeparatorLabel = dateLabel,
                                    seen = isSeenReceiptVisible(
                                        isGroup = state.isGroup,
                                        mine = message.mine,
                                        messageSeq = message.seq,
                                        peerReadSeq = state.peerReadSeq,
                                    ),
                                    formatTime = { timeFormat.format(Date(it)) },
                                    onRetry = viewModel::retryFailed,
                                    imageLoader = imageLoader,
                                    onOpenMedia = onOpenMedia,
                                    onOpenFile = ::openFile,
                                    resolveOriginal = { id ->
                                        state.messages.firstOrNull { it.id == id }
                                    },
                                    onSwipeReply = { viewModel.setReplyTarget(message) },
                                    onLongPress = { actionSheetMessageId = message.id },
                                    onReaction = { emoji -> viewModel.toggleReaction(message, emoji) },
                                    onJumpTo = { targetId ->
                                        if (findMessageReversedIndex(state.messages, targetId) < 0) {
                                            jumpingTargetId = targetId
                                        }
                                        viewModel.jumpToMessage(targetId)
                                    },
                                    isPulsing = pulsingMessageId == message.id,
                                )
                            }
                            if (state.hasMore) {
                                item(key = "load-older") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        LaunchedEffect(state.messages.firstOrNull()?.seq) {
                                            viewModel.loadOlder()
                                        }
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = listState.firstVisibleItemIndex > 2,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 16.dp, end = 16.dp),
                        ) {
                            SmallFloatingActionButton(
                                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary,
                                shape = NexaShapes.roundedFull,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                                )
                            }
                        }
                    }
                    if (state.peerTyping) {
                        Text(
                            text = stringResource(R.string.chat_typing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    state.error?.let { error ->
                        Text(
                            text = chatErrorMessage(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    PendingAttachmentStrip(
                        items = state.pendingAttachments,
                        imageLoader = imageLoader,
                        onRemove = viewModel::removePendingAttachment,
                    )
                    if (editingMessage != null) {
                        EditMessageBanner(
                            snippet = editingMessage.content.orEmpty(),
                            onCancel = {
                                editingMessageId = null
                                editInput = ""
                            },
                        )
                    } else {
                        state.replyTarget?.let { target ->
                            ReplyPreviewBar(target = target, onCancel = viewModel::clearReplyTarget)
                        }
                    }
                    MessageInputBar(
                        input = if (editingMessage != null) editInput else state.input,
                        onInputChange = {
                            if (editingMessage != null) editInput = it else viewModel.onInputChange(it)
                        },
                        onSend = {
                            if (editingMessage != null) {
                                viewModel.editMessage(editingMessage.id, editInput.trim())
                            } else {
                                viewModel.send()
                            }
                        },
                        onAttach = { showMediaPicker = true },
                        isEditing = editingMessage != null,
                        originalEditContent = editingMessage?.content.orEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun EditMessageBanner(
    snippet: String,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_message_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_cancel_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardConversationSheet(
    onDismiss: () -> Unit,
    onSelectConversation: (String) -> Unit,
    inboxViewModel: InboxViewModel = hiltViewModel(),
) {
    val inboxState by inboxViewModel.uiState.asFlow().collectAsStateWithLifecycle(
        inboxViewModel.uiState.value ?: InboxUiState(),
    )
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredConversations = remember(inboxState.conversations, query) {
        inboxState.conversations.filter { conv ->
            ForwardFilter.matchesQuery(conv.displayName, query)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.forward_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.forward_search_placeholder)) },
                shape = NexaShapes.searchBarPill,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            ) {
                when (ForwardPickerRules.body(
                    inboxState.loading && inboxState.conversations.isEmpty(),
                    inboxState.error != null && inboxState.conversations.isEmpty(),
                    filteredConversations.size,
                )) {
                    ForwardPickerRules.Body.LOADING -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    ForwardPickerRules.Body.ERROR -> EmptyState(
                        message = stringResource(R.string.error_generic),
                        iconResId = R.drawable.ic_empty_chat,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = inboxViewModel::refresh,
                    )
                    ForwardPickerRules.Body.EMPTY -> EmptyState(
                        message = stringResource(
                            if (query.isBlank()) R.string.inbox_empty else R.string.forward_no_matches,
                        ),
                        iconResId = R.drawable.ic_empty_chat,
                    )
                    ForwardPickerRules.Body.LIST -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredConversations, key = { it.id }) { conv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = conv.id }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InitialAvatar(name = conv.displayName, avatarUrl = conv.displayAvatar, size = 40.dp)
                        Text(
                            text = conv.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        RadioButton(
                            selected = (conv.id == selectedId),
                            onClick = { selectedId = conv.id },
                        )
                    }
                }
            }
                }
            }

            Button(
                onClick = { selectedId?.let { onSelectConversation(it) } },
                enabled = selectedId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            ) {
                Text(stringResource(R.string.forward_send))
            }
        }
    }
}

@Composable
private fun SearchOverlay(
    searchState: ChatSearchState,
    timeFormat: SimpleDateFormat,
    onSelectResult: (ChatMessage) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp)
            .padding(horizontal = 16.dp),
    ) {
        if (SearchUiRules.shouldShowLoading(searchState.loading, searchState.results.size)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (SearchUiRules.shouldShowError(searchState.error != null, searchState.results.size)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = stringResource(R.string.search_in_chat_error),
                    iconResId = R.drawable.ic_empty_chat,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
        } else if (SearchUiRules.shouldShowQueryTooShort(searchState.query)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = stringResource(R.string.search_in_chat_too_short),
                    iconResId = R.drawable.ic_empty_chat,
                )
            }
        } else if (SearchUiRules.shouldShowEmpty(searchState.error != null, searchState.loading, searchState.results.size, searchState.query)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = stringResource(R.string.search_in_chat_no_results),
                    iconResId = R.drawable.ic_empty_chat,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchState.results, key = { it.id }) { msg ->
                    SearchResultRow(
                        message = msg,
                        query = searchState.query,
                        timeFormat = timeFormat,
                        onClick = { onSelectResult(msg) },
                    )
                    HorizontalDivider()
                }
                if (searchState.hasMore) {
                    item {
                        LaunchedEffect(Unit) { onLoadMore() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    message: ChatMessage,
    query: String,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val content = message.content.orEmpty()
    val ranges = remember(content, query) {
        SearchHighlightHelper.findHighlightRanges(content, query)
    }

    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val highlightedText = remember(content, ranges, primaryContainer) {
        buildAnnotatedString {
            var lastIdx = 0
            for (range in ranges) {
                val start = range.start.coerceIn(0, content.length)
                val end = range.end.coerceIn(0, content.length)
                if (start > lastIdx) {
                    append(content.substring(lastIdx, start))
                }
                withStyle(
                    SpanStyle(
                        background = primaryContainer,
                        color = onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(content.substring(start, end))
                }
                lastIdx = end
            }
            if (lastIdx < content.length) {
                append(content.substring(lastIdx))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message.senderDisplayName.orEmpty().ifBlank { stringResource(R.string.chat_title) },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = timeFormat.format(Date(message.createdAtEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = highlightedText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val MAX_VISUAL_PICK = 10
private val PICK_VISUAL_REQUEST = PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    message: ChatMessage,
    showSender: Boolean,
    groupPosition: MessageGroupPosition,
    dateSeparatorLabel: String?,
    seen: Boolean,
    formatTime: (Long) -> String,
    onRetry: (String) -> Unit,
    imageLoader: coil.ImageLoader,
    onOpenMedia: (AttachmentDto) -> Unit,
    onOpenFile: (AttachmentDto) -> Unit,
    resolveOriginal: (String) -> ChatMessage?,
    onSwipeReply: () -> Unit,
    onLongPress: () -> Unit,
    onReaction: (String) -> Unit,
    onJumpTo: (String) -> Unit,
    isPulsing: Boolean = false,
) {
    val mine = message.mine
    val isTombstone = MessageUiRules.isTombstone(message.isDeleted, message.deletedAtEpochMs)
    val replyable = isMessageReplyable(message.status) && !isTombstone
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd && replyable) onSwipeReply()
            false
        },
        positionalThreshold = { distance -> distance * SWIPE_REPLY_THRESHOLD },
    )
    val verticalPadding = when (groupPosition) {
        MessageGroupPosition.FIRST, MessageGroupPosition.SINGLE -> 4.dp
        MessageGroupPosition.MIDDLE, MessageGroupPosition.LAST -> 1.5.dp
    }

    val replyLabel = stringResource(R.string.action_reply)
    val actionsLabel = stringResource(R.string.action_reply)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (dateSeparatorLabel != null) {
            DateSeparator(
                label = dateSeparatorLabel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwipeToDismissBox(
            state = swipeState,
            enableDismissFromStartToEnd = replyable,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                if (swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = replyLabel,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = verticalPadding),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    modifier = Modifier.semantics {
                        if (!isTombstone) {
                            customActions = listOf(
                                CustomAccessibilityAction(actionsLabel) {
                                    onLongPress()
                                    true
                                },
                            )
                        }
                    },
                ) {
                    ChatBubble(
                        message = message,
                        showSender = showSender,
                        seen = seen,
                        groupPosition = groupPosition,
                        formatTime = formatTime,
                        onRetry = onRetry,
                        imageLoader = imageLoader,
                        onOpenMedia = onOpenMedia,
                        onOpenFile = onOpenFile,
                        resolveOriginal = resolveOriginal,
                        onLongPress = onLongPress,
                        onJumpTo = onJumpTo,
                        isPulsing = isPulsing,
                    )
                }
            }
        }
        if (!isTombstone && message.reactions.isNotEmpty()) {
            ReactionChipsRow(
                reactions = message.reactions,
                mine = mine,
                onToggle = onReaction,
            )
        }
    }
}

private const val SWIPE_REPLY_THRESHOLD = 0.35f

@Composable
private fun MessageInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    isEditing: Boolean = false,
    originalEditContent: String = "",
) {
    val canSubmit = if (isEditing) {
        input.isNotBlank() && input.trim() != originalEditContent.trim()
    } else {
        input.isNotBlank()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isEditing) {
                IconButton(onClick = onAttach) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.cd_attach),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.edit_message_title else R.string.chat_input_hint,
                        ),
                    )
                },
                shape = NexaShapes.composerPill,
                maxLines = 4,
            )
            AnimatedVisibility(
                visible = canSubmit,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                val sendIcon = if (isEditing) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send
                val sendDesc = stringResource(if (isEditing) R.string.edit_message_title else R.string.cd_send)
                IconButton(
                    onClick = onSend,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        imageVector = sendIcon,
                        contentDescription = sendDesc,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Visual container role for message bubble background color selection.
 */
internal enum class BubbleContainerColorRole {
    ERROR,
    MINE,
    PEER,
}

/**
 * Pure UI presentation rule: resolves the bubble container color role.
 * FAILED messages always highlight as ERROR regardless of sender;
 * otherwise outgoing (mine) messages use MINE tint and incoming use PEER tint.
 */
internal fun resolveBubbleContainerRole(status: DeliveryStatus, mine: Boolean): BubbleContainerColorRole = ChatBubbleRules.resolveBubbleContainerRole(status, mine)

/**
 * Pure UI presentation rule: only messages confirmed SENT by the server can be replied to or pinned.
 * Pending (SENDING) and FAILED outbox messages lack permanent server IDs.
 */
internal fun isMessageReplyable(status: DeliveryStatus): Boolean = ChatBubbleRules.isMessageReplyable(status)

/**
 * Pure UI presentation rule: retry affordance is visible and actionable only on FAILED messages.
 */
internal fun isRetryAffordanceVisible(status: DeliveryStatus): Boolean = ChatBubbleRules.isRetryAffordanceVisible(status)

/**
 * Pure UI presentation rule: Seen ("Đã xem") receipt display.
 * Only shown for:
 * 1) 1-1 direct chat (NOT group chat, where watermark cannot identify individual reader)
 * 2) Current user's own sent messages (mine == true)
 * 3) Message sequence number is <= the peer's confirmed lastReadSeq
 */
internal fun isSeenReceiptVisible(
    isGroup: Boolean,
    mine: Boolean,
    messageSeq: Long?,
    peerReadSeq: Long,
): Boolean = ChatBubbleRules.isSeenReceiptVisible(isGroup, mine, messageSeq, peerReadSeq)

/**
 * Pure UI presentation rule: resolves the 0-indexed position within a reverseLayout LazyColumn
 * for [targetId]. Under reverseLayout, index 0 is the newest message (last element of [messages]).
 * Returns -1 if [targetId] is not found in [messages].
 */
internal fun findMessageReversedIndex(messages: List<ChatMessage>, targetId: String): Int = ChatBubbleRules.findMessageReversedIndex(messages, targetId)
