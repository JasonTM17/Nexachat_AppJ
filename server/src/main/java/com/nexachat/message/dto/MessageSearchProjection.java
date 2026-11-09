package com.nexachat.message.dto;

import java.time.Instant;
import java.util.UUID;

public interface MessageSearchProjection {
    UUID getId();
    UUID getConversationId();
    Long getSeq();
    UUID getSenderId();
    String getSenderDisplayName();
    String getType();
    String getContent();
    UUID getClientMessageId();
    UUID getReplyToId();
    Instant getCreatedAt();
    Instant getEditedAt();
    Instant getDeletedAt();
    Boolean getMentionAll();
}
