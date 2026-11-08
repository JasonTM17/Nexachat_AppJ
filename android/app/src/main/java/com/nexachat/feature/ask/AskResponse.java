package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Response body for the conversation ask endpoint. */
public final class AskResponse {
    @Nullable private final String answer;
    @Nullable private final List<AskCitation> citations;
    @Nullable private final String mode;
    @Nullable private final AskUsage usage;

    AskResponse() {
        this(null, Collections.emptyList(), null, null);
    }

    public AskResponse(
            @Nullable String answer,
            @Nullable List<AskCitation> citations,
            @Nullable String mode,
            @Nullable AskUsage usage) {
        this.answer = answer;
        this.citations = citations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(citations));
        this.mode = mode;
        this.usage = usage;
    }

    @Nullable
    public String getAnswer() {
        return answer;
    }

    public List<AskCitation> getCitations() {
        return citations == null ? Collections.emptyList() : citations;
    }

    @Nullable
    public String getMode() {
        return mode;
    }

    @Nullable
    public AskUsage getUsage() {
        return usage;
    }
}
