package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AskRequest(
        @NotBlank(message = "question must not be blank")
        @Size(min = 1, max = 500, message = "question must be between 1 and 500 characters")
        String question,
        SummaryLanguage language,
        @Valid
        @Size(max = 3)
        List<AskPriorTurn> priorTurns) {

    public AskRequest {
        language = language == null ? SummaryLanguage.AUTO : language;
        priorTurns = AskPriorTurns.sanitize(priorTurns);
    }

    public AskRequest(String question, SummaryLanguage language) {
        this(question, language, List.of());
    }
}
