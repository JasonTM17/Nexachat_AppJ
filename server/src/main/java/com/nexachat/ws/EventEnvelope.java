package com.nexachat.ws;

import java.time.Instant;
import java.util.UUID;

/**
 * Frozen envelope (plan §11.2) wrapping every server→client frame so clients
 * parse ONE shape:
 *
 * <pre>{@code
 * { "eventType":"MESSAGE_CREATED", "conversationId":"c-42",
 *   "serverTs":"2026-11-03T09:12:04Z",
 *   "payload": { /* MessageResponse / ReadReceiptPayload / TypingSignal … *\/ } }
 * }</pre>
 */
public record EventEnvelope(WsEventType eventType,
                            UUID conversationId,
                            Instant serverTs,
                            Object payload) {

    public static EventEnvelope of(WsEventType eventType, UUID conversationId, Object payload) {
        return new EventEnvelope(eventType, conversationId, Instant.now(), payload);
    }
}
