package com.nexachat.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One glossary term. {@code replacement} empty/null means "keep this term
 * verbatim"; non-empty means "always render it as this".
 */
public record GlossaryTermDto(
        @NotBlank @Size(max = 100) String term,
        @Size(max = 100) String replacement) {
}
