package com.nexachat.message.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncRequest(
        @NotEmpty(message = "watermarks list must not be empty")
        @Size(max = 100, message = "watermarks list must not exceed 100 items")
        @Valid
        List<ConversationWatermark> watermarks,

        Integer limitPerConv,

        Instant since
) {
    public record ConversationWatermark(
            @NotNull(message = "conversationId is required")
            UUID conversationId,

            @NotNull(message = "lastSeq is required")
            Long lastSeq
    ) {
    }
}
