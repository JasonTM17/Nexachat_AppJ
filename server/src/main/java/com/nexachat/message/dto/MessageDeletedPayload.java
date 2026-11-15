package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Recall frame. {@code changeVersion} and {@code generation} are present for a
 * versioned tombstone so a client can apply it without treating the frame as
 * an unversioned downgrade. Both are omitted together when absent.
 * {@code messageCreatedAt} is the recalled message's creation instant; the
 * outbound membership gate uses it to deliver the frame only to members
 * admitted at that time, and it is omitted when unset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDeletedPayload(
        UUID messageId,
        Instant deletedAt,
        Long changeVersion,
        Long generation,
        Instant messageCreatedAt) {

    public MessageDeletedPayload(UUID messageId, Instant deletedAt) {
        this(messageId, deletedAt, null, null, null);
    }

    public MessageDeletedPayload(UUID messageId, Instant deletedAt,
                                 Long changeVersion, Long generation) {
        this(messageId, deletedAt, changeVersion, generation, null);
    }
}
