package com.nexachat.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexachat.R
import com.nexachat.data.ChatMessage
import com.nexachat.data.ReactionChip

/**
 * Message-interaction composables (phase-10): reaction bar/chips, reply
 * preview + in-bubble quote, and the pinned banner. Everything reads
 * `MaterialTheme.colorScheme` — light/dark safe, no hardcoded colors.
 * Interactive pieces keep ≥48dp touch targets via explicit `size(48.dp)`
 * hit boxes around smaller visuals.
 */

/** Emojis offered in the long-press reaction bar — fixed, mainstream set. */
internal val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Emoji row inside the message dropdown menu. Each glyph sits in a 48dp
 * hit box; [onPick] also dismisses the parent menu.
 */
@Composable
internal fun ReactionBarRow(onPick: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        REACTION_EMOJIS.forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/**
 * Aggregated reaction chips under a bubble. Mine-side messages align chips
 * to the end; `reactedByMe` chips get the primary highlight + border.
 */
@Composable
internal fun ReactionChipsRow(
    reactions: List<ReactionChip>,
    mine: Boolean,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            reactions.forEach { chip ->
                // 48dp transparent hit box around the ~30dp visual chip.
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onToggle(chip.emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = if (chip.reactedByMe) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = if (chip.reactedByMe) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(text = chip.emoji, style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = chip.count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-bubble reply quote: accent bar + sender + one-line snippet.
 * [resolved] false means the original was neither embedded nor loaded —
 * the quote still renders (dimmed) and the tap does nothing.
 */
@Composable
internal fun ReplyQuoteBlock(
    senderName: String?,
    snippet: String?,
    resolved: Boolean,
    onJump: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(bottom = 4.dp)
            .then(
                if (resolved) {
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.action_jump_to_message),
                        role = Role.Button,
                        onClick = onJump,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        // 48dp accent bar keeps the tappable quote at the minimum hit height.
        Row(modifier = Modifier.widthIn(max = 240.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = senderName ?: stringResource(R.string.reply_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (resolved) 1f else 0.6f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = snippet ?: stringResource(R.string.reply_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (resolved) 0.9f else 0.5f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Composer-attached reply draft bar — shown while [target] is set, removed
 * by the X or by send.
 */
@Composable
internal fun ReplyPreviewBar(
    target: ChatViewModel.ReplyTarget,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.senderName
                        ?: stringResource(R.string.reply_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = target.snippet.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_cancel_reply),
                )
            }
        }
    }
}

/**
 * Banner under the app bar while pins exist: pin icon + "Pinned" label +
 * current pin snippet + position badge (1/3). Each tap jumps to the shown
 * pin then advances to the next — cycling through all pins.
 */
@Composable
internal fun PinnedBanner(
    pins: List<ChatMessage>,
    index: Int,
    onJump: () -> Unit,
) {
    if (pins.isEmpty()) return
    val shown = pins[index % pins.size]
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.action_jump_to_message),
                role = Role.Button,
                onClick = onJump,
            ),
    ) {
        // ≥48dp row so the whole banner is a comfortable tap target.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = stringResource(R.string.cd_pinned),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.pinned_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = shown.content ?: shown.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pins.size > 1) {
                Text(
                    text = stringResource(
                        R.string.pinned_count,
                        index % pins.size + 1,
                        pins.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
