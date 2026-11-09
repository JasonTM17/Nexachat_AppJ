package com.nexachat.message.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One member mention, with a UTF-16 range in the stored message content. */
public record MessageMentionRequest(
        @NotNull UUID userId,
        @NotNull Integer offset,
        @NotNull Integer length
) {
}
