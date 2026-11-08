package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** Request body for the conversation ask endpoint. */
public final class AskRequest {
    private final String question;
    @Nullable private final String language;

    public AskRequest(@NonNull String question, @Nullable String language) {
        this.question = Objects.requireNonNull(question, "question");
        this.language = language;
    }

    @NonNull
    public String getQuestion() {
        return question;
    }

    @Nullable
    public String getLanguage() {
        return language;
    }
}
