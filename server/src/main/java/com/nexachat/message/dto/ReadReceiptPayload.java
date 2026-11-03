package com.nexachat.message.dto;

import java.util.UUID;

/** Payload of a {@code MESSAGE_READ} envelope: member {@code userId} has read up to {@code upToSeq}. */
public record ReadReceiptPayload(UUID userId, long upToSeq) {
}
