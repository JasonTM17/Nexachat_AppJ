package com.nexachat.feature.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.data.ChatMessage
import com.nexachat.data.DeliveryStatus
import com.nexachat.feature.media.MediaPickerSheet
import com.nexachat.feature.media.MessageAttachmentContent
import com.nexachat.feature.media.PendingAttachmentStrip
import com.nexachat.feature.media.rememberAuthedImageLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Direct and group chat (plan §12.1, FR-C1..C7 + phase-09 media). Everything
 * renders from [ChatViewModel.UiState] — Room is the render source, so the
 * list is already correct on rotation/restart without any fetch.
 *
 * `reverseLayout` puts seq 0-indexed newest at the bottom; the "hasMore" item
 * sits at the visual top and auto-loads the next cursor page when composed.
 * Attach flow: the attach button opens [MediaPickerSheet]; picked items stage in
 * [PendingAttachmentStrip] until each upload completes and the outbox bubble
 * takes over. IMAGE/VIDEO/AUDIO bubbles navigate to the media viewer; FILE
 * bubbles download to cache and hand off to ACTION_VIEW.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit = {},
    onOpenMedia: (AttachmentDto) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val imageLoader = rememberAuthedImageLoader()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMediaPicker by rememberSaveable { mutableStateOf(false) }

    // Picker results are identical for direct and group chat — one path.
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
                Toast.makeText(context, R.string.error_attachment_open, Toast.LENGTH_SHORT)
                    .show()
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
                Toast.makeText(context, R.string.error_attachment_open, Toast.LENGTH_SHORT)
                    .show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.peerName ?: stringResource(R.string.chat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    // Group conversations get an entry to member management.
                    if (state.isGroup) {
                        IconButton(onClick = { onOpenDetail(conversationId) }) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = stringResource(R.string.cd_group_info),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (!state.connected) {
                Text(
                    text = stringResource(R.string.chat_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(
                    items = state.messages.asReversed(),
                    key = { it.id },
                ) { message ->
                    MessageBubble(
                        message = message,
                        showSender = state.isGroup && !message.mine,
                        formatTime = { timeFormat.format(Date(it)) },
                        onRetry = viewModel::retryFailed,
                        imageLoader = imageLoader,
                        onOpenMedia = onOpenMedia,
                        onOpenFile = ::openFile,
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
            MessageInputBar(
                input = state.input,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onAttach = { showMediaPicker = true },
            )
        }
    }
}

private const val MAX_VISUAL_PICK = 10
private val PICK_VISUAL_REQUEST = PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showSender: Boolean,
    formatTime: (Long) -> String,
    onRetry: (String) -> Unit,
    imageLoader: coil.ImageLoader,
    onOpenMedia: (AttachmentDto) -> Unit,
    onOpenFile: (AttachmentDto) -> Unit,
) {
    val mine = message.mine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp,
            ),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // GROUP chats name the sender above incoming bubbles.
                if (showSender && !message.senderDisplayName.isNullOrBlank()) {
                    Text(
                        text = message.senderDisplayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (message.attachments.isNotEmpty() &&
                    message.type != MessageDto.TYPE_TEXT
                ) {
                    MessageAttachmentContent(
                        message = message,
                        imageLoader = imageLoader,
                        onOpenMedia = onOpenMedia,
                        onOpenFile = onOpenFile,
                    )
                } else {
                    Text(
                        text = message.content ?: message.type,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formatTime(message.createdAtEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mine) {
                        DeliveryMark(message = message, onRetry = onRetry)
                    }
                }
            }
        }
    }
}

/** Send-state indicator: spinner for SENDING, tappable "!" for FAILED. */
@Composable
private fun DeliveryMark(message: ChatMessage, onRetry: (String) -> Unit) {
    when (message.status) {
        DeliveryStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp,
        )
        DeliveryStatus.FAILED -> Text(
            text = "!",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { onRetry(message.clientMessageId ?: message.id) },
        )
        DeliveryStatus.SENT -> Unit
    }
}

@Composable
private fun MessageInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = stringResource(R.string.cd_attach),
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 4,
        )
        IconButton(
            onClick = onSend,
            enabled = input.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send),
            )
        }
    }
}
