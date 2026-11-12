package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Recall frame. {@code changeVersion} and {@code generation} are present for a
 * versioned tombstone so a client can apply it without treating the frame as
 * an unversioned downgrade. Both are omitted together when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDeletedPayload(
        UUID messageId,
        Instant deletedAt,
        Long changeVersion,
        Long generation) {

    public MessageDeletedPayload(UUID messageId, Instant deletedAt) {
        this(messageId, deletedAt, null, null);
    }
}
