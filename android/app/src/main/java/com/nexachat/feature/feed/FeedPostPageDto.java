package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import java.util.List;

/** Cursor-paged feed page, newest first. */
public final class FeedPostPageDto {
    @Nullable private final List<FeedPostDto> items;
    @Json(name = "nextCursor") @Nullable private final String nextCursor;

    public FeedPostPageDto() {
        this(null, null);
    }

    public FeedPostPageDto(
            @Nullable List<FeedPostDto> items, @Nullable String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<FeedPostDto> getItems() {
        return items == null ? List.of() : items;
    }

    @Nullable public String getNextCursor() { return nextCursor; }
}
