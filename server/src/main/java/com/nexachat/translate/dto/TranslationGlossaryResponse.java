package com.nexachat.translate.dto;

import java.util.List;
import java.util.UUID;

public record TranslationGlossaryResponse(
        UUID conversationId,
        List<GlossaryTermDto> terms) {
}
