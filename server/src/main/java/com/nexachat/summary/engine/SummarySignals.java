package com.nexachat.summary.engine;

public record SummarySignals(String replyToMessageId, int reactionCount, boolean pinned) {
    public SummarySignals {
        reactionCount = Math.max(0, reactionCount);
    }

    public static SummarySignals none() {
        return new SummarySignals(null, 0, false);
    }
}
