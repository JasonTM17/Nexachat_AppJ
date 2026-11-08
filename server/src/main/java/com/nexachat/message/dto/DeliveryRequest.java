package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Delivery-watermark payload shared by REST {@code POST /conversations/{id}/delivered}
 * and STOMP {@code /app/conversations/{id}/delivered} and {@code /app/chat.delivery.ack}.
 */
public record DeliveryRequest(UUID conversationId, Long upToSeq) {
    public DeliveryRequest(Long upToSeq) {
        this(null, upToSeq);
    }
}
