package com.nexachat.message.dto;

import jakarta.validation.constraints.NotNull;

public record EditMessageRequest(@NotNull String content) {
}
