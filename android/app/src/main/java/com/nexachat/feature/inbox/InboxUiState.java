package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.ConversationSummary;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable inbox screen state. */
public final class InboxUiState {

    private final List<ConversationSummary> conversations;
    @Nullable private final String currentUserId;
    private final boolean loading;
    @Nullable private final ApiError error;
    private final boolean hasMore;
    private final boolean loadingMore;
    @Nullable private final ApiError nextPageError;
    private final InboxFilter selectedFilter;

    public InboxUiState() {
        this(Collections.emptyList(), null, true, null);
    }

    public InboxUiState(
            List<ConversationSummary> conversations,
            @Nullable String currentUserId,
            boolean loading,
            @Nullable ApiError error) {
        this(conversations, currentUserId, loading, error, false, false, null, InboxFilter.ALL);
    }

    private InboxUiState(
            List<ConversationSummary> conversations,
            @Nullable String currentUserId,
            boolean loading,
            @Nullable ApiError error,
            boolean hasMore,
            boolean loadingMore,
            @Nullable ApiError nextPageError,
            @NonNull InboxFilter selectedFilter) {
        this.conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        this.currentUserId = currentUserId;
        this.loading = loading;
        this.error = error;
        this.hasMore = hasMore;
        this.loadingMore = loadingMore;
        this.nextPageError = nextPageError;
        this.selectedFilter = Objects.requireNonNull(selectedFilter, "selectedFilter");
    }

    public List<ConversationSummary> getConversations() {
        return conversations;
    }

    @Nullable
    public String getCurrentUserId() {
        return currentUserId;
    }

    public boolean getLoading() {
        return loading;
    }

    public boolean isLoading() {
        return loading;
    }

    @Nullable
    public ApiError getError() {
        return error;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public boolean isLoadingMore() {
        return loadingMore;
    }

    @Nullable
    public ApiError getNextPageError() {
        return nextPageError;
    }

    /** Sum of per-row unread — the app-level badge count; archived rows are hidden. */
    public int getTotalUnread() {
        int sum = 0;
        for (ConversationSummary row : conversations) {
            if (row.isArchived()) continue;
            sum += row.getUnreadCount();
        }
        return sum;
    }

    @NonNull
    public InboxFilter getSelectedFilter() {
        return selectedFilter;
    }

    @NonNull
    public List<ConversationSummary> getVisibleConversations() {
        return InboxFilterRules.apply(conversations, selectedFilter);
    }

    public InboxUiState withSelectedFilter(@NonNull InboxFilter filter) {
        return new InboxUiState(
                conversations, currentUserId, loading, error, hasMore, loadingMore,
                nextPageError, filter);
    }

    public InboxUiState withConversations(List<ConversationSummary> next, boolean nextLoading) {
        return new InboxUiState(
                next, currentUserId, nextLoading, error, hasMore, loadingMore,
                nextPageError, selectedFilter);
    }

    public InboxUiState withCurrentUserId(@Nullable String userId) {
        return new InboxUiState(
                conversations, userId, loading, error, hasMore, loadingMore,
                nextPageError, selectedFilter);
    }

    public InboxUiState withError(@Nullable ApiError nextError) {
        return new InboxUiState(
                conversations, currentUserId, loading, nextError,
                hasMore, loadingMore, nextPageError, selectedFilter);
    }

    public InboxUiState withRefreshStarted() {
        return new InboxUiState(
                conversations, currentUserId, true, null, hasMore, loadingMore,
                nextPageError, selectedFilter);
    }

    public InboxUiState withRefreshResult(boolean nextHasMore) {
        return new InboxUiState(
                conversations, currentUserId, false, null, nextHasMore, false, null,
                selectedFilter);
    }

    public InboxUiState withRefreshFailure(@Nullable ApiError nextError) {
        return new InboxUiState(
                conversations, currentUserId, false, nextError, false, false, null,
                selectedFilter);
    }

    public InboxUiState withNextPageStarted() {
        return new InboxUiState(
                conversations, currentUserId, loading, error, hasMore, true, null,
                selectedFilter);
    }

    public InboxUiState withNextPageResult(boolean nextHasMore) {
        return new InboxUiState(
                conversations, currentUserId, loading, error, nextHasMore, false, null,
                selectedFilter);
    }

    public InboxUiState withNextPageFailure(@Nullable ApiError nextError) {
        return new InboxUiState(
                conversations, currentUserId, loading, error, hasMore, false, nextError,
                selectedFilter);
    }

    public InboxUiState withNextPageIdle() {
        return new InboxUiState(
                conversations, currentUserId, loading, error, hasMore, false, nextPageError,
                selectedFilter);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InboxUiState that)) {
            return false;
        }
        return loading == that.loading
                && Objects.equals(conversations, that.conversations)
                && Objects.equals(currentUserId, that.currentUserId)
                && Objects.equals(error, that.error)
                && hasMore == that.hasMore
                && loadingMore == that.loadingMore
                && Objects.equals(nextPageError, that.nextPageError)
                && selectedFilter == that.selectedFilter;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                conversations, currentUserId, loading, error, hasMore, loadingMore,
                nextPageError, selectedFilter);
    }
}
