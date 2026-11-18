package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import java.util.List;

/** Cursor-paged comment page, oldest first. */
public final class FeedCommentPageDto {
    @Nullable private final List<FeedCommentDto> items;
    @Json(name = "nextCursor") @Nullable private final String nextCursor;

    public FeedCommentPageDto() {
        this(null, null);
    }

    public FeedCommentPageDto(
            @Nullable List<FeedCommentDto> items, @Nullable String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<FeedCommentDto> getItems() {
        return items == null ? List.of() : items;
    }

    @Nullable public String getNextCursor() { return nextCursor; }
}
