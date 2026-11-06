package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ConversationsPageDto {
    @Nullable private final List<ConversationInboxDto> items;
    @Nullable private final String nextCursor;
    private final boolean hasMore;

    public ConversationsPageDto() {
        this(List.of(), null, false);
    }

    public ConversationsPageDto(@Nullable List<ConversationInboxDto> items, @Nullable String nextCursor, boolean hasMore) {
        this.items = items == null ? List.of() : items;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    @NonNull public List<ConversationInboxDto> getItems() {
        return items;
    }

    @Nullable public String getNextCursor() {
        return nextCursor;
    }

    public boolean getHasMore() {
        return hasMore;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConversationsPageDto that)) {
            return false;
        }
        return Objects.equals(items, that.items)
                && Objects.equals(nextCursor, that.nextCursor)
                && hasMore == that.hasMore;
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, nextCursor, hasMore);
    }
}
