package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** One page of visible polls, newest first. */
public final class PollPage {

    private final List<Poll> items;
    @Nullable private final String nextBefore;

    public PollPage(@NonNull List<Poll> items, @Nullable String nextBefore) {
        this.items = List.copyOf(items);
        this.nextBefore = nextBefore;
    }

    @NonNull public List<Poll> getItems() { return items; }
    @Nullable public String getNextBefore() { return nextBefore; }
    public boolean hasMore() { return nextBefore != null && !nextBefore.isBlank(); }
}
