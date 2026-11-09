package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable paging state for one archive tab. */
public final class ArchivePageState {

    private final List<ArchivePage.Item> items;
    @Nullable private final String cursor;
    private final boolean hasMore;
    private final boolean loading;
    private final boolean loaded;
    @Nullable private final ArchiveRepository.Failure error;

    public ArchivePageState() {
        this(Collections.emptyList(), null, true, false, false, null);
    }

    public ArchivePageState(
            List<ArchivePage.Item> items,
            @Nullable String cursor,
            boolean hasMore,
            boolean loading,
            boolean loaded,
            @Nullable ArchiveRepository.Failure error) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.cursor = cursor;
        this.hasMore = hasMore;
        this.loading = loading;
        this.loaded = loaded;
        this.error = error;
    }

    public List<ArchivePage.Item> getItems() { return items; }
    @Nullable public String getCursor() { return cursor; }
    public boolean hasMore() { return hasMore; }
    public boolean isLoading() { return loading; }
    public boolean isLoaded() { return loaded; }
    @Nullable public ArchiveRepository.Failure getError() { return error; }
    public boolean isEmpty() { return loaded && items.isEmpty() && error == null && !loading; }
}
