package com.nexachat.core.ui.components;

import com.nexachat.data.ChatMessage;

import java.util.List;
import java.util.Locale;

/**
 * Pure UI presentation rules for chat message grouping, bubble corner radii,
 * date separators, and relative timestamps.
 *
 * <p>Overloads provide convenient defaults for Java callers.
 */
public final class ChatUiRules {

    private static final long DEFAULT_MAX_GROUP_INTERVAL_MS = 5 * 60 * 1000L;
    private static final float RADIUS_LARGE_DP = 16f;
    private static final float RADIUS_SMALL_DP = 4f;

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
     * Pure UI presentation rule: returns the asymmetric bubble corner radii (in dp)
     * based on the message's grouping position and whether it is outgoing ({@code mine}).
     *
     * <p>The tail corner uses the small radius; the other three use the large radius.
     */
    public static BubbleCornerRadii resolveBubbleShape(
            MessageGroupPosition position,
            boolean mine
    ) {
        float large = RADIUS_LARGE_DP;
        float small = RADIUS_SMALL_DP;
        if (mine) {
            switch (position) {
                case SINGLE:
                case FIRST:
                    return new BubbleCornerRadii(large, large, large, small);
                case MIDDLE:
                    return new BubbleCornerRadii(large, small, large, small);
                case LAST:
                    return new BubbleCornerRadii(large, small, large, large);
                default:
                    throw new AssertionError("Unknown message group position: " + position);
            }
        } else {
            switch (position) {
                case SINGLE:
                case FIRST:
                    return new BubbleCornerRadii(large, large, small, large);
                case MIDDLE:
                    return new BubbleCornerRadii(small, large, small, large);
                case LAST:
                    return new BubbleCornerRadii(small, large, large, large);
                default:
                    throw new AssertionError("Unknown message group position: " + position);
            }
        }
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

    /**
     * Corner radii (in dp) for a chat bubble. The tail corner carries the small radius;
     * the remaining three corners carry the large radius.
     */
    public static final class BubbleCornerRadii {

        public final float topStartDp;
        public final float topEndDp;
        public final float bottomStartDp;
        public final float bottomEndDp;

        public BubbleCornerRadii(
                float topStartDp,
                float topEndDp,
                float bottomStartDp,
                float bottomEndDp
        ) {
            this.topStartDp = topStartDp;
            this.topEndDp = topEndDp;
            this.bottomStartDp = bottomStartDp;
            this.bottomEndDp = bottomEndDp;
        }

        @Override
        public String toString() {
            return "BubbleCornerRadii(topStartDp=" + topStartDp
                    + ", topEndDp=" + topEndDp
                    + ", bottomStartDp=" + bottomStartDp
                    + ", bottomEndDp=" + bottomEndDp + ")";
        }
    }
}
