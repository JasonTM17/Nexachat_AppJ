package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "question must not be blank")
        @Size(min = 1, max = 500, message = "question must be between 1 and 500 characters")
        String question,
        SummaryLanguage language) {

    public AskRequest {
        language = language == null ? SummaryLanguage.AUTO : language;
    }
}
