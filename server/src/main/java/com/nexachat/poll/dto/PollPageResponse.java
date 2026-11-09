package com.nexachat.poll.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Newest-first poll list. {@code nextBefore} is the id to echo as
 * {@code before} for the next older page and is omitted on the last page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollPageResponse(
        List<PollResponse> items,
        UUID nextBefore
) {
}
