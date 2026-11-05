package com.nexachat.feature.summary

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.nexachat.R
import com.nexachat.core.ui.SummaryUiRules
import com.nexachat.summary.SummaryActionItem
import com.nexachat.summary.SummaryItem
import com.nexachat.summary.SummaryResponse
import com.nexachat.summary.SummaryScope
import com.nexachat.summary.SummaryTextFormatter
import com.nexachat.summary.SummaryUiState
import com.nexachat.summary.SummaryViewModel
import java.util.Locale

@Composable
private fun <T> LiveData<T>.observeAsState(initial: T): State<T> {
    val state = remember { mutableStateOf(initial) }
    DisposableEffect(this) {
        val observer = Observer<T> { value ->
            if (value != null) {
                state.value = value
            }
        }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}

/**
 * Material 3 ModalBottomSheet displaying conversation catch-up summary.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SummaryBottomSheet(
    viewModel: SummaryViewModel,
    messageSeqMap: Map<String, Long?>,
    localMessageIds: Set<String>,
    onDismiss: () -> Unit,
    onJumpToMessage: (String) -> Unit,
    onShareIntoChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.state().observeAsState(SummaryUiState.idle(SummaryScope.UNREAD, null))
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Header Row: Sparkle + Title + Badges + Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.summary_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                    }

                    // Badges: Local vs AI
                    val summary = uiState.summary
                    val isLlm = SummaryUiRules.isLlmGenerated(summary?.generatedBy)
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isLlm) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.summary_badge_ai),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = stringResource(R.string.summary_badge_local),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = {
                        viewModel.dismiss()
                        onDismiss()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_summary_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Scope Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryScope.values().forEach { scope ->
                    val isSelected = (uiState.scope == scope)
                    val label = when (scope) {
                        SummaryScope.UNREAD -> stringResource(R.string.summary_scope_unread)
                        SummaryScope.LAST_N -> stringResource(R.string.summary_scope_last_n)
                        SummaryScope.TODAY -> stringResource(R.string.summary_scope_today)
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                val limit = if (scope == SummaryScope.LAST_N) 50 else null
                                viewModel.request(scope, limit)
                            }
                        },
                        label = { Text(label) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            // Content Area based on UI State
            when (uiState.status) {
                SummaryUiState.Status.LOADING -> {
                    SummarySkeleton()
                }
                SummaryUiState.Status.ERROR -> {
                    val err = uiState.error
                    if (err != null) {
                        SummaryErrorView(
                            error = err,
                            onRetry = { viewModel.retry() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                SummaryUiState.Status.READY -> {
                    val summary = uiState.summary
                    if (summary != null) {
                        SummaryContentSections(
                            summary = summary,
                            messageSeqMap = messageSeqMap,
                            localMessageIds = localMessageIds,
                            onCitationClick = { messageId ->
                                viewModel.dismiss()
                                onDismiss()
                                onJumpToMessage(messageId)
                            },
                        )
                    }
                }
                SummaryUiState.Status.IDLE -> {
                    SummarySkeleton()
                }
            }

            // Sticky Bottom Actions Bar (Copy, Share, Regenerate)
            val summary = uiState.summary
            val canAct = SummaryUiRules.canShareOrCopy(uiState.status, summary)

            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Regenerate icon button
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.size(48.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.summary_action_regenerate),
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Copy button
                OutlinedButton(
                    onClick = {
                        if (summary != null) {
                            val shareText = SummaryTextFormatter.toShareText(summary, Locale.getDefault())
                            clipboardManager.setText(AnnotatedString(shareText))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(
                                context,
                                context.getString(R.string.summary_copied_to_clipboard),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = canAct,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.summary_action_copy))
                }

                // Share into chat button
                Button(
                    onClick = {
                        if (summary != null) {
                            val shareText = SummaryTextFormatter.toShareText(summary, Locale.getDefault())
                            viewModel.dismiss()
                            onDismiss()
                            onShareIntoChat(shareText)
                        }
                    },
                    enabled = canAct,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_action_share),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryContentSections(
    summary: SummaryResponse,
    messageSeqMap: Map<String, Long?>,
    localMessageIds: Set<String>,
    onCitationClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Overview
        if (summary.overview.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_section_overview),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = summary.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        // 2. Key Points
        if (summary.keyPoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_section_key_points),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                summary.keyPoints.forEach { item ->
                    SummaryItemRow(
                        item = item,
                        messageSeqMap = messageSeqMap,
                        localMessageIds = localMessageIds,
                        onCitationClick = onCitationClick,
                    )
                }
            }
        }

        // 3. Decisions
        if (summary.decisions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_section_decisions),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                summary.decisions.forEach { item ->
                    SummaryItemRow(
                        item = item,
                        messageSeqMap = messageSeqMap,
                        localMessageIds = localMessageIds,
                        onCitationClick = onCitationClick,
                    )
                }
            }
        }

        // 4. Action Items
        if (summary.actionItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_section_action_items),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                summary.actionItems.forEach { action ->
                    SummaryActionItemRow(
                        action = action,
                        messageSeqMap = messageSeqMap,
                        localMessageIds = localMessageIds,
                        onCitationClick = onCitationClick,
                    )
                }
            }
        }

        // 5. Open Questions
        if (summary.questions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.summary_section_questions),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                summary.questions.forEach { item ->
                    SummaryItemRow(
                        item = item,
                        messageSeqMap = messageSeqMap,
                        localMessageIds = localMessageIds,
                        onCitationClick = onCitationClick,
                    )
                }
            }
        }

        // 6. Topics
        if (summary.topics.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.summary_section_topics),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    summary.topics.forEach { topic ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = "#$topic",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryItemRow(
    item: SummaryItem,
    messageSeqMap: Map<String, Long?>,
    localMessageIds: Set<String>,
    onCitationClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, end = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.messageIds.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.messageIds.forEach { msgId ->
                        CitationChip(
                            messageId = msgId,
                            seq = messageSeqMap[msgId],
                            isAvailable = SummaryUiRules.isCitationJumpable(msgId, localMessageIds),
                            onClick = { onCitationClick(msgId) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryActionItemRow(
    action: SummaryActionItem,
    messageSeqMap: Map<String, Long?>,
    localMessageIds: Set<String>,
    onCitationClick: (String) -> Unit,
) {
    val checked = remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { checked.value = !checked.value }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked.value,
            onCheckedChange = { checked.value = it },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.size(24.dp).padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (checked.value) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = if (checked.value) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )

            // Owner / deadline tag
            val tagText = buildString {
                if (!action.ownerUserId.isNullOrBlank()) {
                    append("@${action.ownerUserId.take(6)}")
                }
                if (!action.dueHint.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(action.dueHint)
                }
            }
            if (tagText.isNotBlank()) {
                Text(
                    text = tagText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (action.messageIds.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    action.messageIds.forEach { msgId ->
                        CitationChip(
                            messageId = msgId,
                            seq = messageSeqMap[msgId],
                            isAvailable = SummaryUiRules.isCitationJumpable(msgId, localMessageIds),
                            onClick = { onCitationClick(msgId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationChip(
    messageId: String,
    seq: Long?,
    isAvailable: Boolean,
    onClick: () -> Unit,
) {
    val chipLabel = if (seq != null) "#$seq" else messageId.take(4)
    val jumpLabel = stringResource(R.string.cd_summary_citation_jump)

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAvailable) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .semantics {
                if (isAvailable) {
                    this.onClick(label = jumpLabel, action = { onClick(); true })
                }
            }
            .then(if (isAvailable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            text = if (isAvailable) {
                stringResource(R.string.summary_citation_chip, chipLabel)
            } else {
                stringResource(R.string.summary_citation_unavailable)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isAvailable) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
