package com.nexachat.poll.dto;

import java.util.UUID;

/**
 * Flat vote projection used to build poll results in one query per page
 * without loading the vote entities or their lazy associations.
 */
public record PollVoteRow(
        UUID pollId,
        UUID optionId,
        UUID userId
) {
}
