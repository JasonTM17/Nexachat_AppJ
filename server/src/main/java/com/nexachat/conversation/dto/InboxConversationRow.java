package com.nexachat.conversation.dto;

import com.nexachat.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal projection for the inbox query: one row per conversation the caller
 * belongs to, carrying the caller's own read watermark and the denormalized
 * last-message pointers (plan §9.3.5 — no MAX(seq) per conversation).
 */
public record InboxConversationRow(
        UUID id,
        Conversation.Type type,
        String title,
        String avatarUrl,
        UUID lastMessageId,
        Long lastMessageSeq,
        Instant createdAt,
        long lastReadSeq
) {
}
