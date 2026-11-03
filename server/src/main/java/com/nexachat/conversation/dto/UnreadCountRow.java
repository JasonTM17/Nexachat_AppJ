package com.nexachat.conversation.dto;

import java.util.UUID;

/** Internal projection: unread count ({@code messages.seq > lastReadSeq}) per conversation. */
public record UnreadCountRow(UUID conversationId, Long unreadCount) {
}
