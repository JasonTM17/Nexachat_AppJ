package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import java.util.List;

/** Cursor-paged starred list. */
public final class StarredPageDto {
    @Nullable private final List<StarredMessageDto> items;
    @Json(name = "nextCursor") @Nullable private final String nextCursor;

    public StarredPageDto() {
        this(null, null);
    }

    public StarredPageDto(
            @Nullable List<StarredMessageDto> items, @Nullable String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<StarredMessageDto> getItems() {
        return items == null ? List.of() : items;
    }

    @Nullable public String getNextCursor() {
        return nextCursor;
    }
}
