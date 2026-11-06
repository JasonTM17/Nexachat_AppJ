package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class MessagePageDto {
    @Nullable private final List<MessageDto> items;
    @Nullable private final Long nextBeforeSeq;
    private final boolean hasMore;

    public MessagePageDto() {
        this(List.of(), null, false);
    }

    public MessagePageDto(@Nullable List<MessageDto> items, @Nullable Long nextBeforeSeq, boolean hasMore) {
        this.items = items == null ? List.of() : items;
        this.nextBeforeSeq = nextBeforeSeq;
        this.hasMore = hasMore;
    }

    public MessagePageDto(java.util.List<MessageDto> items, boolean hasMore) {
        this(items, null, hasMore);
    }

    @NonNull public List<MessageDto> getItems() {
        return items;
    }

    @Nullable public Long getNextBeforeSeq() {
        return nextBeforeSeq;
    }

    public boolean getHasMore() {
        return hasMore;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MessagePageDto that)) {
            return false;
        }
        return Objects.equals(items, that.items)
                && Objects.equals(nextBeforeSeq, that.nextBeforeSeq)
                && hasMore == that.hasMore;
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, nextBeforeSeq, hasMore);
    }
}
