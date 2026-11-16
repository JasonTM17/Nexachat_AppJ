package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import java.util.List;

/** Cursor-paged `GET /reports/mine` response. */
public final class ReportPageDto {
    @Nullable private final List<ReportDto> items;
    @Json(name = "nextCursor") @Nullable private final String nextCursor;

    public ReportPageDto() {
        this(null, null);
    }

    public ReportPageDto(@Nullable List<ReportDto> items, @Nullable String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<ReportDto> getItems() {
        return items == null ? List.of() : items;
    }

    @Nullable public String getNextCursor() {
        return nextCursor;
    }
}
