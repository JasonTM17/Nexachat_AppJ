package com.nexachat.message.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EditMessageRequest(
        @NotNull String content,
        @Valid @Size(max = 20) List<MessageMentionRequest> mentions,
        Boolean mentionAll
) {
    public EditMessageRequest(@NotNull String content) {
        this(content, null, null);
    }
}
