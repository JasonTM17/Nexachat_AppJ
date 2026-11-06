package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
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

    public InboxUiState() {
        this(Collections.emptyList(), null, true, null);
    }

    public InboxUiState(
            List<ConversationSummary> conversations,
            @Nullable String currentUserId,
            boolean loading,
            @Nullable ApiError error) {
        this.conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        this.currentUserId = currentUserId;
        this.loading = loading;
        this.error = error;
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

    /** Sum of per-row unread — the app-level badge count. */
    public int getTotalUnread() {
        int sum = 0;
        for (ConversationSummary row : conversations) {
            sum += row.getUnreadCount();
        }
        return sum;
    }

    public InboxUiState withConversations(List<ConversationSummary> next, boolean nextLoading) {
        return new InboxUiState(next, currentUserId, nextLoading, error);
    }

    public InboxUiState withCurrentUserId(@Nullable String userId) {
        return new InboxUiState(conversations, userId, loading, error);
    }

    public InboxUiState withError(@Nullable ApiError nextError) {
        return new InboxUiState(conversations, currentUserId, loading, nextError);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InboxUiState that)) {
            return false;
        }
        return loading == that.loading
                && Objects.equals(conversations, that.conversations)
                && Objects.equals(currentUserId, that.currentUserId)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversations, currentUserId, loading, error);
    }
}
