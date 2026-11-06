package com.nexachat.core.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.nexachat.data.ChatMessage
import java.util.Locale

/**
 * Visual position of a message within a sequence of consecutive messages from the same sender.
 */
enum class MessageGroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

/**
 * Pure UI presentation rule: resolves [MessageGroupPosition] for a message at [index]
 * in a chronological list of [messages] (oldest at index 0, newest at index messages.size - 1).
 *
 * Consecutive messages from the same sender within [maxGroupIntervalMs] (default 5 min)
 * are grouped together.
 */
fun resolveGroupPosition(
    messages: List<ChatMessage>,
    index: Int,
    maxGroupIntervalMs: Long = 5 * 60 * 1000L,
): MessageGroupPosition {
    if (index !in messages.indices) return MessageGroupPosition.SINGLE
    val current = messages[index]

    val prev = messages.getOrNull(index - 1)
    val hasPrevSame = prev != null &&
        prev.senderId == current.senderId &&
        prev.mine == current.mine &&
        (current.createdAtEpochMs - prev.createdAtEpochMs) in 0..maxGroupIntervalMs

    val next = messages.getOrNull(index + 1)
    val hasNextSame = next != null &&
        next.senderId == current.senderId &&
        next.mine == current.mine &&
        (next.createdAtEpochMs - current.createdAtEpochMs) in 0..maxGroupIntervalMs

    return when {
        !hasPrevSame && !hasNextSame -> MessageGroupPosition.SINGLE
        !hasPrevSame && hasNextSame -> MessageGroupPosition.FIRST
        hasPrevSame && hasNextSame -> MessageGroupPosition.MIDDLE
        else -> MessageGroupPosition.LAST
    }
}

/**
 * Pure UI presentation rule: returns the asymmetric [RoundedCornerShape] based on
 * the message's grouping position and whether it is outgoing ([mine]).
 */
fun resolveBubbleShape(position: MessageGroupPosition, mine: Boolean): RoundedCornerShape {
    val radiusLarge = 16.dp
    val radiusSmall = 4.dp

    return if (mine) {
        when (position) {
            MessageGroupPosition.SINGLE -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusLarge,
                bottomStart = radiusLarge,
                bottomEnd = radiusSmall,
            )
            MessageGroupPosition.FIRST -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusLarge,
                bottomStart = radiusLarge,
                bottomEnd = radiusSmall,
            )
            MessageGroupPosition.MIDDLE -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusSmall,
                bottomStart = radiusLarge,
                bottomEnd = radiusSmall,
            )
            MessageGroupPosition.LAST -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusSmall,
                bottomStart = radiusLarge,
                bottomEnd = radiusLarge,
            )
        }
    } else {
        when (position) {
            MessageGroupPosition.SINGLE -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusLarge,
                bottomStart = radiusSmall,
                bottomEnd = radiusLarge,
            )
            MessageGroupPosition.FIRST -> RoundedCornerShape(
                topStart = radiusLarge,
                topEnd = radiusLarge,
                bottomStart = radiusSmall,
                bottomEnd = radiusLarge,
            )
            MessageGroupPosition.MIDDLE -> RoundedCornerShape(
                topStart = radiusSmall,
                topEnd = radiusLarge,
                bottomStart = radiusSmall,
                bottomEnd = radiusLarge,
            )
            MessageGroupPosition.LAST -> RoundedCornerShape(
                topStart = radiusSmall,
                topEnd = radiusLarge,
                bottomStart = radiusLarge,
                bottomEnd = radiusLarge,
            )
        }
    }
}

/**
 * Pure UI presentation rule: determines if a date separator must be rendered between
 * [prevMessageEpochMs] and [currentMessageEpochMs] (in chronological order).
 * Returns true if [prevMessageEpochMs] is null (first message in thread) or crosses midnight.
 */
fun shouldShowDateSeparator(prevMessageEpochMs: Long?, currentMessageEpochMs: Long): Boolean =
    ChatTimeRules.shouldShowDateSeparator(prevMessageEpochMs, currentMessageEpochMs)

data class DateSeparatorLabels(
    val today: String,
    val yesterday: String,
)

/**
 * Pure UI presentation rule: formats a date separator label.
 * Returns [DateSeparatorLabels.today] if on same day as [nowEpochMs],
 * [DateSeparatorLabels.yesterday] if on previous calendar day,
 * "dd/MM" if in the same calendar year, or "dd/MM/yyyy" otherwise.
 */
fun resolveDateSeparatorLabel(
    messageEpochMs: Long,
    nowEpochMs: Long,
    labels: DateSeparatorLabels,
    locale: Locale = Locale.getDefault(),
): String = ChatTimeRules.resolveDateSeparatorLabel(messageEpochMs, nowEpochMs, labels, locale)

/**
 * Pure UI presentation rule: relative timestamp formatting for inbox rows.
 * - < 1 min: [justNowLabel] ("Vừa xong" / "Just now")
 * - Same day: "HH:mm"
 * - Yesterday: [yesterdayLabel] ("Hôm qua" / "Yesterday")
 * - Same year: "dd/MM"
 * - Older: "dd/MM/yy"
 */
fun resolveRelativeTimeLabel(
    epochMs: Long,
    nowEpochMs: Long,
    justNowLabel: String,
    yesterdayLabel: String,
    locale: Locale = Locale.getDefault(),
): String = ChatTimeRules.resolveRelativeTimeLabel(epochMs, nowEpochMs, justNowLabel, yesterdayLabel, locale)
