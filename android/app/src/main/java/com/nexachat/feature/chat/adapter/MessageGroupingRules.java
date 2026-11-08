package com.nexachat.feature.chat.adapter;

import androidx.annotation.NonNull;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import java.util.List;

/** Presentation grouping for rows, with system events acting as run boundaries. */
public final class MessageGroupingRules {

    private MessageGroupingRules() {
    }

    @NonNull
    public static MessageGroupPosition resolveGroupPosition(
            @NonNull List<ChatMessage> messages,
            int index
    ) {
        if (index < 0 || index >= messages.size()) {
            return MessageGroupPosition.SINGLE;
        }

        ChatMessage current = messages.get(index);
        if (MessageViewTypeMapper.isSystemMessage(current)) {
            return MessageGroupPosition.SINGLE;
        }

        MessageGroupPosition existing = ChatUiRules.resolveGroupPosition(messages, index);
        boolean hasPrevious = existing == MessageGroupPosition.MIDDLE
                || existing == MessageGroupPosition.LAST;
        boolean hasNext = existing == MessageGroupPosition.FIRST
                || existing == MessageGroupPosition.MIDDLE;
        if (index > 0 && startsNewVisualRun(messages.get(index - 1), current)) {
            hasPrevious = false;
        }
        if (index + 1 < messages.size() && startsNewVisualRun(current, messages.get(index + 1))) {
            hasNext = false;
        }

        if (!hasPrevious && !hasNext) {
            return MessageGroupPosition.SINGLE;
        }
        if (!hasPrevious) {
            return MessageGroupPosition.FIRST;
        }
        return hasNext ? MessageGroupPosition.MIDDLE : MessageGroupPosition.LAST;
    }

    private static boolean startsNewVisualRun(ChatMessage earlier, ChatMessage later) {
        return MessageViewTypeMapper.isSystemMessage(earlier)
                || MessageViewTypeMapper.isSystemMessage(later)
                || ChatUiRules.shouldShowDateSeparator(
                        earlier.getCreatedAtEpochMs(),
                        later.getCreatedAtEpochMs());
    }
}
