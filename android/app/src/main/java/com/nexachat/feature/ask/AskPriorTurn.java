package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Previous sheet Q&A sent so the server can resolve follow-up references. */
public final class AskPriorTurn {
    private final String question;
    private final String answer;

    public AskPriorTurn(@NonNull String question, @NonNull String answer) {
        this.question = Objects.requireNonNull(question, "question");
        this.answer = Objects.requireNonNull(answer, "answer");
    }

    @NonNull
    public String getQuestion() {
        return question;
    }

    @NonNull
    public String getAnswer() {
        return answer;
    }
}
