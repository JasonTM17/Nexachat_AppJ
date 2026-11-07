package com.nexachat.feature.chat;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.ChatMessage;
import java.util.List;
import java.util.Objects;

/** In-conversation search state; results are newest-first and hydrated for rendering. */
public final class ChatSearchState {

    private final String query;
    private final boolean loading;
    private final List<ChatMessage> results;
    private final boolean hasMore;
    @Nullable private final ApiError error;

    public ChatSearchState() {
        this("", false, List.of(), false, null);
    }

    public ChatSearchState(
            String query,
            boolean loading,
            List<ChatMessage> results,
            boolean hasMore,
            @Nullable ApiError error) {
        this.query = Objects.requireNonNull(query, "query");
        this.loading = loading;
        this.results = List.copyOf(Objects.requireNonNull(results, "results"));
        this.hasMore = hasMore;
        this.error = error;
    }

    public String getQuery() {
        return query;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean getLoading() {
        return loading;
    }

    public List<ChatMessage> getResults() {
        return results;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public boolean getHasMore() {
        return hasMore;
    }

    @Nullable
    public ApiError getError() {
        return error;
    }

    public ChatSearchState withLoading(boolean nextLoading, @Nullable ApiError nextError) {
        return new ChatSearchState(query, nextLoading, results, hasMore, nextError);
    }

    public ChatSearchState withResults(List<ChatMessage> nextResults, boolean nextHasMore) {
        return new ChatSearchState(query, false, nextResults, nextHasMore, error);
    }

    public ChatSearchState withError(@Nullable ApiError nextError) {
        return new ChatSearchState(query, false, results, hasMore, nextError);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChatSearchState that)) {
            return false;
        }
        return loading == that.loading
                && hasMore == that.hasMore
                && query.equals(that.query)
                && results.equals(that.results)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, loading, results, hasMore, error);
    }

    @Override
    public String toString() {
        return "ChatSearchState(query=" + query + ", loading=" + loading
                + ", results=" + results + ", hasMore=" + hasMore + ", error=" + error + ")";
    }
}
