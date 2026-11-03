package com.nexachat.ws.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Frame pushed to {@code /user/queue/errors} when a SEND is rejected
 * (plan §11.2): {@code {code, clientMessageId?}} — the client matches it to
 * the optimistic SENDING entry and marks it FAILED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsErrorResponse(String code, UUID clientMessageId) {
}
