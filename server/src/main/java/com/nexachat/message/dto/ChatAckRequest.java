package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Read-watermark payload for STOMP {@code /app/chat.read.ack}: "I have read up to seq N in conversationId".
 */
public record ChatAckRequest(UUID conversationId, Long upToSeq) {
}
