package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import java.util.List;

/** Reflective Moshi bean for poll creation. Null body fields are omitted. */
public final class CreatePollRequest {
    private final String question;
    private final List<String> options;
    private final Boolean multipleChoice;
    private final Boolean anonymous;
    @Nullable private final String closesAt;

    public CreatePollRequest() {
        this(null, List.of(), Boolean.FALSE, Boolean.FALSE, null);
    }

    public CreatePollRequest(
            String question,
            List<String> options,
            Boolean multipleChoice,
            Boolean anonymous,
            @Nullable String closesAt) {
        this.question = question;
        this.options = options;
        this.multipleChoice = multipleChoice;
        this.anonymous = anonymous;
        this.closesAt = closesAt;
    }

    public String getQuestion() { return question; }
    public List<String> getOptions() { return options; }
    public Boolean getMultipleChoice() { return multipleChoice; }
    public Boolean getAnonymous() { return anonymous; }
    @Nullable public String getClosesAt() { return closesAt; }
}
