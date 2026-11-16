package com.nexachat.message.dto;

import java.time.Instant;

/**
 * One item of {@code GET /messages/starred}: the hydrated message plus when
 * the viewer starred it.
 */
public record StarredMessageView(Instant starredAt, MessageResponse message) {
}
