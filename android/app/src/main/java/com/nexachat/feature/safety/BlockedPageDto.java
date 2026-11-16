package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import java.util.List;

/** Cursor-paged blocked list. */
public final class BlockedPageDto {
    @Nullable private final List<BlockedUserDto> items;
    @Json(name = "nextCursor") @Nullable private final String nextCursor;

    public BlockedPageDto() {
        this(null, null);
    }

    public BlockedPageDto(
            @Nullable List<BlockedUserDto> items, @Nullable String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<BlockedUserDto> getItems() {
        return items == null ? List.of() : items;
    }

    @Nullable public String getNextCursor() {
        return nextCursor;
    }
}
