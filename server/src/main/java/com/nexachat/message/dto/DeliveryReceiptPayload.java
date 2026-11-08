package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Payload of a {@code MESSAGE_DELIVERED} envelope: member {@code userId} has received up to {@code upToSeq}.
 */
public record DeliveryReceiptPayload(UUID userId, long upToSeq) {
}
