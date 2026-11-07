package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/** One F4 search page, newest-first and already hydrated for rendering. */
public final class MessageSearchResult {
    private final List<ChatMessage> items;
    private final boolean hasMore;
    @Nullable private final Long nextBeforeSeq;

    public MessageSearchResult(List<ChatMessage> items, boolean hasMore, @Nullable Long nextBeforeSeq) {
        this.items = List.copyOf(items);
        this.hasMore = hasMore;
        this.nextBeforeSeq = nextBeforeSeq;
    }
    public List<ChatMessage> getItems() { return items; }
    public boolean getHasMore() { return hasMore; }
    @Nullable public Long getNextBeforeSeq() { return nextBeforeSeq; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MessageSearchResult that)) return false;
        return hasMore == that.hasMore && items.equals(that.items)
                && Objects.equals(nextBeforeSeq, that.nextBeforeSeq);
    }
    @Override public int hashCode() { return Objects.hash(items, hasMore, nextBeforeSeq); }
}
