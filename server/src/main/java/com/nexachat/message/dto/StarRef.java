package com.nexachat.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code message_stars} row as the page step sees it: which message the
 * viewer starred and when. The service re-fetches viewer-scoped rows by id
 * and zips them back onto these refs in page order.
 */
public record StarRef(UUID messageId, Instant starredAt) {
}
