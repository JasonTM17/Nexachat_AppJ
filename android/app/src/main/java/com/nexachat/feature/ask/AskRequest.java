package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Request body for the conversation ask endpoint. */
public final class AskRequest {
    private final String question;
    @Nullable private final String language;
    @Nullable private final List<AskPriorTurn> priorTurns;

    public AskRequest(@NonNull String question, @Nullable String language) {
        this(question, language, null);
    }

    public AskRequest(
            @NonNull String question,
            @Nullable String language,
            @Nullable List<AskPriorTurn> priorTurns) {
        this.question = Objects.requireNonNull(question, "question");
        this.language = language;
        this.priorTurns = priorTurns == null || priorTurns.isEmpty()
                ? null
                : Collections.unmodifiableList(new ArrayList<>(priorTurns));
    }

    @NonNull
    public String getQuestion() {
        return question;
    }

    @Nullable
    public String getLanguage() {
        return language;
    }

    @Nullable
    public List<AskPriorTurn> getPriorTurns() {
        return priorTurns;
    }
}
