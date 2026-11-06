package com.nexachat.core.ui.components;

import com.nexachat.data.ChatMessage;

import java.util.List;
import java.util.Objects;

/**
 * Pure presentation rules for grouping adjacent chat messages.
 */
public final class MessageGroupRules {

    private MessageGroupRules() {
    }

    public static MessageGroupPosition resolveGroupPosition(
            List<ChatMessage> messages,
            int index,
            long maxGroupIntervalMs
    ) {
        if (index < 0 || index >= messages.size()) {
            return MessageGroupPosition.SINGLE;
        }

        ChatMessage current = messages.get(index);
        boolean hasPrevious = index > 0
                && areGrouped(messages.get(index - 1), current, maxGroupIntervalMs);
        boolean hasNext = index + 1 < messages.size()
                && areGrouped(current, messages.get(index + 1), maxGroupIntervalMs);

        if (!hasPrevious && !hasNext) {
            return MessageGroupPosition.SINGLE;
        }
        if (!hasPrevious) {
            return MessageGroupPosition.FIRST;
        }
        if (hasNext) {
            return MessageGroupPosition.MIDDLE;
        }
        return MessageGroupPosition.LAST;
    }

    private static boolean areGrouped(
            ChatMessage earlier,
            ChatMessage later,
            long maxGroupIntervalMs
    ) {
        if (!Objects.equals(earlier.getSenderId(), later.getSenderId())
                || earlier.getMine() != later.getMine()) {
            return false;
        }

        long intervalMs = later.getCreatedAtEpochMs() - earlier.getCreatedAtEpochMs();
        return intervalMs >= 0 && intervalMs <= maxGroupIntervalMs;
    }
}
