package com.nexachat.feature.chat.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.core.ui.components.DateSeparatorLabels;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.feature.chat.ChatBubbleRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Expands chronological {@link ChatMessage} rows into adapter items including date separators.
 */
public final class MessageListMapper {

    private MessageListMapper() {
    }

    @NonNull
    public static List<MessageListItem> buildDisplayItems(
            @NonNull List<ChatMessage> chronological,
            boolean isGroup,
            long peerReadSeq,
            @Nullable String pulsingMessageId,
            @NonNull DateSeparatorLabels dateLabels,
            long nowEpochMs
    ) {
        return buildDisplayItems(
                chronological, isGroup, peerReadSeq, 0L, pulsingMessageId, dateLabels, nowEpochMs, -1, 0);
    }

    @NonNull
    public static List<MessageListItem> buildDisplayItems(
            @NonNull List<ChatMessage> chronological,
            boolean isGroup,
            long peerReadSeq,
            long peerDeliveredSeq,
            @Nullable String pulsingMessageId,
            @NonNull DateSeparatorLabels dateLabels,
            long nowEpochMs
    ) {
        return buildDisplayItems(
                chronological, isGroup, peerReadSeq, peerDeliveredSeq,
                pulsingMessageId, dateLabels, nowEpochMs, -1, 0);
    }

    /**
     * @param unreadDividerBeforeMessageIndex message index to place the unread divider before, or -1
     * @param unreadDividerCount              label count when the divider is shown
     */
    @NonNull
    public static List<MessageListItem> buildDisplayItems(
            @NonNull List<ChatMessage> chronological,
            boolean isGroup,
            long peerReadSeq,
            long peerDeliveredSeq,
            @Nullable String pulsingMessageId,
            @NonNull DateSeparatorLabels dateLabels,
            long nowEpochMs,
            int unreadDividerBeforeMessageIndex,
            int unreadDividerCount
    ) {
        if (chronological.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageListItem> rows = new ArrayList<>();
        for (int index = 0; index < chronological.size(); index++) {
            ChatMessage message = chronological.get(index);
            ChatMessage previous = index > 0 ? chronological.get(index - 1) : null;
            if (ChatUiRules.shouldShowDateSeparator(
                    previous != null ? previous.getCreatedAtEpochMs() : null,
                    message.getCreatedAtEpochMs()
            )) {
                String label = ChatUiRules.resolveDateSeparatorLabel(
                        message.getCreatedAtEpochMs(),
                        nowEpochMs,
                        dateLabels
                );
                rows.add(MessageListItem.dateSeparator(label, message.getCreatedAtEpochMs()));
            }
            if (index == unreadDividerBeforeMessageIndex && unreadDividerCount > 0) {
                rows.add(MessageListItem.unreadDivider(unreadDividerCount));
            }

            MessageGroupPosition groupPosition = MessageGroupingRules.resolveGroupPosition(chronological, index);
            boolean showSender = isGroup
                    && !message.getMine()
                    && !MessageViewTypeMapper.isSystemMessage(message)
                    && (groupPosition == MessageGroupPosition.FIRST
                    || groupPosition == MessageGroupPosition.SINGLE);
            boolean seen = ChatBubbleRules.isSeenReceiptVisible(
                    isGroup,
                    message.getMine(),
                    message.getSeq(),
                    peerReadSeq
            );
            boolean delivered = ChatBubbleRules.isDeliveredReceiptVisible(
                    isGroup,
                    message.getMine(),
                    message.getSeq(),
                    peerDeliveredSeq
            );
            boolean pulsing = pulsingMessageId != null && pulsingMessageId.equals(message.getId());
            rows.add(MessageListItem.message(message, groupPosition, showSender, seen, delivered, pulsing));
        }
        return rows;
    }
}
