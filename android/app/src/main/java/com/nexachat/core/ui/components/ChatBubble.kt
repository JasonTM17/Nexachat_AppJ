package com.nexachat.core.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nexachat.call.ui.CallLogResourceResolver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nexachat.R
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.data.ChatMessage
import com.nexachat.data.DeliveryStatus
import androidx.compose.ui.text.font.FontStyle
import com.nexachat.core.ui.MessageUiRules
import com.nexachat.feature.chat.BubbleContainerColorRole
import com.nexachat.feature.chat.ReplyQuoteBlock
import com.nexachat.feature.chat.isRetryAffordanceVisible
import com.nexachat.feature.chat.resolveBubbleContainerRole
import com.nexachat.feature.media.MessageAttachmentContent

/**
 * Standard Date Separator pill for chat streams.
 */
@Composable
fun DateSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Central design-system Message Bubble composable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage,
    showSender: Boolean,
    seen: Boolean,
    groupPosition: MessageGroupPosition,
    formatTime: (Long) -> String,
    onRetry: (String) -> Unit,
    imageLoader: coil.ImageLoader,
    onOpenMedia: (AttachmentDto) -> Unit,
    onOpenFile: (AttachmentDto) -> Unit,
    resolveOriginal: (String) -> ChatMessage?,
    onLongPress: () -> Unit,
    onJumpTo: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPulsing: Boolean = false,
) {
    val context = LocalContext.current
    val mine = message.mine
    val isTombstone = MessageUiRules.isTombstone(message.isDeleted, message.deletedAtEpochMs)
    val isEdited = MessageUiRules.isEdited(message.editedAtEpochMs, message.deletedAtEpochMs)
    val containerRole = resolveBubbleContainerRole(message.status, mine)
    val isRetry = isRetryAffordanceVisible(message.status)
    val bubbleShape = resolveBubbleShape(groupPosition, mine)

    val surfaceColor = when {
        isPulsing -> MaterialTheme.colorScheme.tertiaryContainer
        isTombstone -> MaterialTheme.colorScheme.surfaceContainerHigh
        containerRole == BubbleContainerColorRole.ERROR -> MaterialTheme.colorScheme.errorContainer
        containerRole == BubbleContainerColorRole.MINE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        color = surfaceColor,
        shape = bubbleShape,
        modifier = modifier.combinedClickable(
            onClick = {
                if (isRetry) {
                    onRetry(message.clientMessageId ?: message.id)
                }
            },
            onClickLabel = if (isRetry) stringResource(R.string.action_retry) else null,
            onLongClick = if (!isTombstone) onLongPress else null,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showSender && !message.senderDisplayName.isNullOrBlank()) {
                Text(
                    text = message.senderDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!isTombstone) {
                message.replyToId?.let { replyId ->
                    val original = resolveOriginal(replyId)
                    val snippet = message.replyToSnippet ?: original?.content
                    val senderName = message.replyToSenderName ?: original?.senderDisplayName
                    ReplyQuoteBlock(
                        senderName = senderName,
                        snippet = snippet,
                        resolved = original != null || snippet != null || senderName != null,
                        onJump = { onJumpTo(replyId) },
                    )
                }
            }

            if (isTombstone) {
                Text(
                    text = stringResource(R.string.message_deleted),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            } else if (message.attachments.isNotEmpty() && message.type != MessageDto.TYPE_TEXT) {
                MessageAttachmentContent(
                    message = message,
                    imageLoader = imageLoader,
                    onOpenMedia = onOpenMedia,
                    onOpenFile = onOpenFile,
                )
            } else {
                val rawContent = message.content ?: message.type
                val contentText = if (rawContent.startsWith("CALL|")) {
                    val callLog = CallLogResourceResolver.fromSystemMessage(rawContent, mine)
                    stringResource(callLog.resourceId, *callLog.arguments)
                } else {
                    rawContent
                }
                val links = remember(contentText) { extractHttpLinks(contentText) }

                if (links.isEmpty()) {
                    Text(
                        text = contentText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    val linkColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
                    val textColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

                    val annotatedText = buildAnnotatedString {
                        var lastIndex = 0
                        links.forEach { (range, url) ->
                            if (range.first > lastIndex) {
                                append(contentText.substring(lastIndex, range.first))
                            }
                            pushStringAnnotation(tag = "URL", annotation = url)
                            withStyle(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(contentText.substring(range))
                            }
                            pop()
                            lastIndex = range.last + 1
                        }
                        if (lastIndex < contentText.length) {
                            append(contentText.substring(lastIndex))
                        }
                    }

                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    val safeUrl = sanitizeLinkUrl(annotation.item)
                                    if (safeUrl != null) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                                            context.startActivity(intent)
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(context, R.string.error_attachment_open, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isEdited) {
                    Text(
                        text = stringResource(R.string.message_edited),
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                Text(
                    text = formatTime(message.createdAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mine) {
                    BubbleDeliveryMark(message = message, seen = seen)
                }
            }
        }
    }
}

@Composable
private fun BubbleDeliveryMark(message: ChatMessage, seen: Boolean) {
    when (message.status) {
        DeliveryStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        DeliveryStatus.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = stringResource(R.string.action_retry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DeliveryStatus.SENT -> if (seen) {
            Text(
                text = stringResource(R.string.msg_seen),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.cd_delivered),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
