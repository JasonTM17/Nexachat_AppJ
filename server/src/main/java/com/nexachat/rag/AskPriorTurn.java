package com.nexachat.rag;

import jakarta.validation.constraints.Size;

/** Previous sheet Q&A used only to resolve follow-up references in the LLM prompt. */
public record AskPriorTurn(
        @Size(max = 500) String question,
        @Size(max = 2000) String answer) {
}
