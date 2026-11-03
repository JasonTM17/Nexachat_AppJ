package com.nexachat.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.nexachat.R
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.data.ChatMessage

/**
 * Bubble body for media-bearing messages (phase-09). The first attachment
 * drives the visual: IMAGE renders a Coil thumb that opens the fullscreen
 * viewer; VIDEO/AUDIO render a compact row into the same viewer; FILE rows
 * download then hand off to ACTION_VIEW. A caption ([ChatMessage.content])
 * only shows when it is not just the filename echo.
 */
@Composable
fun MessageAttachmentContent(
    message: ChatMessage,
    imageLoader: ImageLoader,
    onOpenMedia: (AttachmentDto) -> Unit,
    onOpenFile: (AttachmentDto) -> Unit,
) {
    val attachment = message.attachments.firstOrNull() ?: return
    Column {
        when (message.type) {
            MessageDto.TYPE_IMAGE -> ImageThumb(
                attachment = attachment,
                imageLoader = imageLoader,
                onClick = { onOpenMedia(attachment) },
            )
            MessageDto.TYPE_VIDEO, MessageDto.TYPE_AUDIO -> MediaRow(
                attachment = attachment,
                icon = if (message.type == MessageDto.TYPE_VIDEO) {
                    Icons.Filled.PlayArrow
                } else {
                    Icons.Filled.MusicNote
                },
                onClick = { onOpenMedia(attachment) },
            )
            else -> MediaRow(
                attachment = attachment,
                icon = Icons.Filled.Description,
                onClick = { onOpenFile(attachment) },
            )
        }
        val caption = message.content?.takeIf {
            it.isNotBlank() && it != attachment.originalName
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ImageThumb(
    attachment: AttachmentDto,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    AsyncImage(
        model = attachment.contentUrl(),
        imageLoader = imageLoader,
        contentDescription = attachment.originalName
            ?: stringResource(R.string.cd_open_attachment),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun MediaRow(
    attachment: AttachmentDto,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.cd_open_attachment),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = attachment.originalName ?: stringResource(R.string.media_attachment),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = formatBytes(attachment.sizeBytes)
            if (size.isNotEmpty()) {
                Text(
                    text = size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Staging strip above the input bar: one card per picked item with live
 * upload percent and a remove-X. Rows disappear when the upload completes
 * (the outbox bubble takes over) or when cancelled.
 */
@Composable
fun PendingAttachmentStrip(
    items: List<PendingAttachment>,
    imageLoader: ImageLoader,
    onRemove: (String) -> Unit,
) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = items, key = { it.localId }) { item ->
            PendingCard(item = item, imageLoader = imageLoader, onRemove = onRemove)
        }
    }
}

@Composable
private fun PendingCard(
    item: PendingAttachment,
    imageLoader: ImageLoader,
    onRemove: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            if (item.type == MessageDto.TYPE_IMAGE) {
                AsyncImage(
                    model = item.uri,
                    imageLoader = imageLoader,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = when (item.type) {
                            MessageDto.TYPE_VIDEO -> Icons.Filled.PlayArrow
                            MessageDto.TYPE_AUDIO -> Icons.Filled.MusicNote
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = item.displayName.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.state == PendingAttachment.PendingState.UPLOADING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = "${(item.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            } else {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // No size() override: the M3 IconButton keeps its 48dp touch
            // target (a size(22) here clamped it to 22dp).
            IconButton(
                onClick = { onRemove(item.localId) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_remove_attachment),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
