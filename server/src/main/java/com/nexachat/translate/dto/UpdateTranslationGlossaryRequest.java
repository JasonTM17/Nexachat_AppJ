package com.nexachat.translate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateTranslationGlossaryRequest(
        @NotNull @Size(max = 50) List<@Valid GlossaryTermDto> terms) {
}
