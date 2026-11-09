package com.nexachat.feature.poll;

import java.util.List;

/** Reflective Moshi bean carrying the complete vote selection. */
public final class VoteRequest {
    private final List<String> optionIds;

    public VoteRequest() {
        this(List.of());
    }

    public VoteRequest(List<String> optionIds) {
        this.optionIds = optionIds;
    }

    public List<String> getOptionIds() { return optionIds; }
}
