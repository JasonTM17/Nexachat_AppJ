package com.nexachat.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code POST /conversations/{id}/polls}. Bean validation covers the
 * wire limits; {@code PollService} trims and re-checks the same bounds, rejects
 * duplicate labels after trimming, and validates {@code closesAt} against the
 * server clock (future and at most 30 days out).
 *
 * @param question       1..200 characters after trimming
 * @param options        2..10 labels, each 1..100 characters after trimming
 * @param multipleChoice false/null = single choice
 * @param anonymous       false/null = voter ids are visible to members
 * @param closesAt        optional expiry, at most 30 days after creation
 */
public record CreatePollRequest(
        @NotBlank @Size(max = 200) String question,
        @NotNull @Size(min = 2, max = 10) List<@NotBlank @Size(max = 100) String> options,
        Boolean multipleChoice,
        Boolean anonymous,
        Instant closesAt
) {
}
