package com.nexachat.poll.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full poll state returned by create, detail, vote, retract and close.
 *
 * <p>{@code status} is computed at read time: {@code OPEN} or {@code CLOSED}
 * when the explicit {@code closedAt} is set OR the optional {@code closesAt}
 * has been reached. {@code voterCount} is the number of distinct voters (a
 * multiple-choice poll can count more option votes than voters).
 * {@code myOptionIds} is always the caller's own selection and never exposes
 * another member's identity.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollResponse(
        UUID id,
        UUID conversationId,
        UUID createdBy,
        String question,
        boolean multipleChoice,
        boolean anonymous,
        String status,
        Instant closesAt,
        Instant closedAt,
        UUID closedBy,
        Instant createdAt,
        long version,
        long voterCount,
        List<PollOptionResponse> options,
        List<UUID> myOptionIds
) {
}
