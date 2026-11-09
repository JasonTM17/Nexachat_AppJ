package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Reflective Moshi bean for the paged poll list. */
public final class PollPageDto {
    @Nullable private final List<PollDto> items;
    @Nullable private final String nextBefore;

    public PollPageDto() {
        this(null, null);
    }

    public PollPageDto(@Nullable List<PollDto> items, @Nullable String nextBefore) {
        this.items = items;
        this.nextBefore = nextBefore;
    }

    @Nullable public List<PollDto> getItems() { return items; }
    @Nullable public String getNextBefore() { return nextBefore; }

    public PollPage toDomain() {
        List<Poll> polls = new ArrayList<>();
        if (items != null) {
            for (PollDto dto : items) {
                Poll poll = dto == null ? null : dto.toDomain();
                if (poll != null) {
                    polls.add(poll);
                }
            }
        }
        return new PollPage(polls, nextBefore);
    }
}
