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

    /**
     * One-pass variant of {@link #resolveGroupPosition(List, int)} over date boundaries
     * precomputed by {@link #dateSeparatorBoundaries(List)}. Identical results, but the
     * calendar-day rule is evaluated once per adjacent pair instead of once per neighbour
     * probe, so a full mapper pass costs O(N) calendar work instead of O(3N).
     */
    @NonNull
    public static MessageGroupPosition resolveGroupPosition(
            @NonNull List<ChatMessage> messages,
            int index,
            @NonNull boolean[] dateBoundaries
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
        if (index > 0 && startsNewVisualRun(messages.get(index - 1), current, dateBoundaries[index])) {
            hasPrevious = false;
        }
        if (index + 1 < messages.size()
                && startsNewVisualRun(current, messages.get(index + 1), dateBoundaries[index + 1])) {
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

    /**
     * Element {@code i} is true when message {@code i} must render after a date separator:
     * the first row ({@code i == 0}) or a calendar-day change from message {@code i - 1}.
     */
    @NonNull
    public static boolean[] dateSeparatorBoundaries(@NonNull List<ChatMessage> messages) {
        boolean[] boundaries = new boolean[messages.size()];
        for (int index = 1; index < boundaries.length; index++) {
            boundaries[index] = ChatUiRules.shouldShowDateSeparator(
                    messages.get(index - 1).getCreatedAtEpochMs(),
                    messages.get(index).getCreatedAtEpochMs());
        }
        if (boundaries.length > 0) {
            boundaries[0] = true;
        }
        return boundaries;
    }

    private static boolean startsNewVisualRun(ChatMessage earlier, ChatMessage later) {
        return MessageViewTypeMapper.isSystemMessage(earlier)
                || MessageViewTypeMapper.isSystemMessage(later)
                || ChatUiRules.shouldShowDateSeparator(
                        earlier.getCreatedAtEpochMs(),
                        later.getCreatedAtEpochMs());
    }

    private static boolean startsNewVisualRun(
            ChatMessage earlier,
            ChatMessage later,
            boolean dateBoundary
    ) {
        return MessageViewTypeMapper.isSystemMessage(earlier)
                || MessageViewTypeMapper.isSystemMessage(later)
                || dateBoundary;
    }
}
