package com.nexachat.ws.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Frame pushed to {@code /user/queue/errors} when a SEND is rejected
 * (plan §11.2): {@code {code, clientMessageId?}} — the client matches it to
 * the optimistic SENDING entry and marks it FAILED.
 *
 * <p>Call-signal rejections additionally carry {@code callId} and
 * {@code conversationId} so the client can attribute the error to the
 * in-progress call instead of dropping it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsErrorResponse(String code, UUID clientMessageId,
                              UUID callId, UUID conversationId) {

    public WsErrorResponse(String code, UUID clientMessageId) {
        this(code, clientMessageId, null, null);
    }
}
