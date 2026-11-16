package com.nexachat.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record TranslateBatchRequest(
        @NotEmpty @Size(max = 20) List<UUID> messageIds,
        @NotBlank @Size(max = 16) String targetLang) {
}
