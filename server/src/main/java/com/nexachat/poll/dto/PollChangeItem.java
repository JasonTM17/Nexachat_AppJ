package com.nexachat.poll.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One revision in the poll change feed. A deleted poll reports only the
 * tombstone fields ({@code poll} is {@code null} and omitted); a live poll
 * carries its full {@link PollResponse} at that revision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollChangeItem(
        UUID pollId,
        long version,
        boolean deleted,
        PollResponse poll
) {
}
