package com.nexachat.poll.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code PUT /conversations/{id}/polls/{pollId}/vote}. The list is the
 * complete selection after the write: one option for a single-choice poll, one
 * to ten distinct options for a multiple-choice poll. Duplicates are ignored;
 * an empty list is rejected here and retracting is {@code DELETE .../vote}.
 */
public record VoteRequest(
        @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> optionIds
) {
}
