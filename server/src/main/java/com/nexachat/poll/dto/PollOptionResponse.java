package com.nexachat.poll.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * One option of a poll. {@code voterIds} is present only for a non-anonymous
 * poll; on an anonymous poll it is {@code null} and therefore omitted from the
 * wire even for the poll creator. {@code voteCount} always equals
 * {@code voterIds.size()} when the list is present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollOptionResponse(
        UUID id,
        int position,
        String label,
        long voteCount,
        List<UUID> voterIds
) {
}
