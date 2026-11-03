package com.nexachat.message.dto;

/**
 * Read-watermark payload shared by REST {@code POST /conversations/{id}/read}
 * and STOMP {@code /app/conversations/{id}/read}: "I have read up to seq N".
 * {@code upToSeq} must be a non-negative global sequence number.
 */
public record ReadRequest(Long upToSeq) {
}
