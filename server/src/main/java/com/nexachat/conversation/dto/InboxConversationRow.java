package com.nexachat.conversation.dto;

import com.nexachat.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal projection for the inbox query: one row per conversation the caller
 * belongs to, carrying the caller's own read watermark, the denormalized
 * last-message pointers (plan §9.3.5 — no MAX(seq) per conversation) and the
 * caller's own mute/archive/pin preferences.
 *
 * <p>{@code muted}/{@code mutedUntil} are the raw stored values; the service
 * applies the lazy expiry before publishing them. {@code pinned} sorts ahead
 * of the activity order and {@code pinnedAt} is {@code null} exactly when the
 * row is not pinned.
 */
public record InboxConversationRow(
        UUID id,
        Conversation.Type type,
        String title,
        String avatarUrl,
        UUID lastMessageId,
        Long lastMessageSeq,
        Instant createdAt,
        long lastReadSeq,
        boolean muted,
        Instant mutedUntil,
        boolean archived,
        boolean pinned,
        Instant pinnedAt
) {
}
