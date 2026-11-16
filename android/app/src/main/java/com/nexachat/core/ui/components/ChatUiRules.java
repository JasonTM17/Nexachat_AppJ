package com.nexachat.core.ui.components;

import com.nexachat.data.ChatMessage;

import java.util.List;
import java.util.Locale;

/**
 * Pure UI presentation rules for chat message grouping, date separators, and
 * relative timestamps.
 *
 * <p>Overloads provide convenient defaults for Java callers.
 */
public final class ChatUiRules {

    private static final long DEFAULT_MAX_GROUP_INTERVAL_MS = 5 * 60 * 1000L;

    private ChatUiRules() {
    }

    /**
     * Pure UI presentation rule: resolves {@link MessageGroupPosition} for a message at
     * {@code index} in a chronological list of {@code messages} (oldest at index 0,
     * newest at index {@code messages.size() - 1}).
     *
     * <p>Consecutive messages from the same sender within the default 5 minute interval
     * are grouped together.
     */
    public static MessageGroupPosition resolveGroupPosition(
            List<ChatMessage> messages,
            int index
    ) {
        return resolveGroupPosition(messages, index, DEFAULT_MAX_GROUP_INTERVAL_MS);
    }

    /**
     * Pure UI presentation rule: resolves {@link MessageGroupPosition} for a message at
     * {@code index} in a chronological list of {@code messages} (oldest at index 0,
     * newest at index {@code messages.size() - 1}).
     *
     * <p>Consecutive messages from the same sender within {@code maxGroupIntervalMs}
     * are grouped together.
     */
    public static MessageGroupPosition resolveGroupPosition(
            List<ChatMessage> messages,
            int index,
            long maxGroupIntervalMs
    ) {
        return MessageGroupRules.resolveGroupPosition(messages, index, maxGroupIntervalMs);
    }

    /**
     * Pure UI presentation rule: determines if a date separator must be rendered between
     * {@code prevMessageEpochMs} and {@code currentMessageEpochMs} (in chronological order).
     * Returns true if {@code prevMessageEpochMs} is null (first message in thread) or crosses midnight.
     */
    public static boolean shouldShowDateSeparator(
            Long prevMessageEpochMs,
            long currentMessageEpochMs
    ) {
        return ChatTimeRules.shouldShowDateSeparator(prevMessageEpochMs, currentMessageEpochMs);
    }

    /**
     * Pure UI presentation rule: formats a date separator label using the default locale.
     * Returns {@link DateSeparatorLabels#getToday()} if on same day as {@code nowEpochMs},
     * {@link DateSeparatorLabels#getYesterday()} if on previous calendar day,
     * "dd/MM" if in the same calendar year, or "dd/MM/yyyy" otherwise.
     */
    public static String resolveDateSeparatorLabel(
            long messageEpochMs,
            long nowEpochMs,
            DateSeparatorLabels labels
    ) {
        return ChatTimeRules.resolveDateSeparatorLabel(messageEpochMs, nowEpochMs, labels);
    }

    /**
     * Pure UI presentation rule: formats a date separator label.
     * Returns {@link DateSeparatorLabels#getToday()} if on same day as {@code nowEpochMs},
     * {@link DateSeparatorLabels#getYesterday()} if on previous calendar day,
     * "dd/MM" if in the same calendar year, or "dd/MM/yyyy" otherwise.
     */
    public static String resolveDateSeparatorLabel(
            long messageEpochMs,
            long nowEpochMs,
            DateSeparatorLabels labels,
            Locale locale
    ) {
        return ChatTimeRules.resolveDateSeparatorLabel(messageEpochMs, nowEpochMs, labels, locale);
    }

    /**
     * Pure UI presentation rule: relative timestamp formatting for inbox rows using the
     * default locale.
     * <ul>
     *   <li>&lt; 1 min: {@code justNowLabel} ("Vừa xong" / "Just now")</li>
     *   <li>Same day: "HH:mm"</li>
     *   <li>Yesterday: {@code yesterdayLabel} ("Hôm qua" / "Yesterday")</li>
     *   <li>Same year: "dd/MM"</li>
     *   <li>Older: "dd/MM/yy"</li>
     * </ul>
     */
    public static String resolveRelativeTimeLabel(
            long epochMs,
            long nowEpochMs,
            String justNowLabel,
            String yesterdayLabel
    ) {
        return ChatTimeRules.resolveRelativeTimeLabel(epochMs, nowEpochMs, justNowLabel, yesterdayLabel);
    }

    /**
     * Pure UI presentation rule: relative timestamp formatting for inbox rows.
     * <ul>
     *   <li>&lt; 1 min: {@code justNowLabel} ("Vừa xong" / "Just now")</li>
     *   <li>Same day: "HH:mm"</li>
     *   <li>Yesterday: {@code yesterdayLabel} ("Hôm qua" / "Yesterday")</li>
     *   <li>Same year: "dd/MM"</li>
     *   <li>Older: "dd/MM/yy"</li>
     * </ul>
     */
    public static String resolveRelativeTimeLabel(
            long epochMs,
            long nowEpochMs,
            String justNowLabel,
            String yesterdayLabel,
            Locale locale
    ) {
        return ChatTimeRules.resolveRelativeTimeLabel(epochMs, nowEpochMs, justNowLabel, yesterdayLabel, locale);
    }
}
