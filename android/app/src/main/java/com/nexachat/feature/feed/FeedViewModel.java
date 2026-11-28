package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

/**
 * The public feed list: newest-first pages, keyword search, author filter,
 * pull-to-refresh, reactions, honest own-post deletion, and live detail sync.
 */
@HiltViewModel
public final class FeedViewModel extends ViewModel {

    /** One-shot outcome for actions on the feed list. */
    public enum Outcome { POST_DELETED, POST_DELETE_FAILED }

    /** One immutable snapshot of the list. */
    public static final class UiState {
        private final List<FeedRepository.Post> items;
        private final boolean loading;
        private final boolean refreshing;
        private final boolean loadingMore;
        private final boolean loadMoreError;
        private final boolean error;
        @Nullable private final String nextCursor;
        @Nullable private final String authorId;
        @Nullable private final String authorName;
        @NonNull private final String query;

        UiState(
                List<FeedRepository.Post> items,
                boolean loading,
                boolean refreshing,
                boolean loadingMore,
                boolean loadMoreError,
                boolean error,
                @Nullable String nextCursor) {
            this(items, loading, refreshing, loadingMore, loadMoreError, error,
                    nextCursor, null, null, "");
        }

        UiState(
                List<FeedRepository.Post> items,
                boolean loading,
                boolean refreshing,
                boolean loadingMore,
                boolean loadMoreError,
                boolean error,
                @Nullable String nextCursor,
                @Nullable String authorId,
                @Nullable String authorName,
                @NonNull String query) {
            this.items = items;
            this.loading = loading;
            this.refreshing = refreshing;
            this.loadingMore = loadingMore;
            this.loadMoreError = loadMoreError;
            this.error = error;
            this.nextCursor = nextCursor;
            this.authorId = authorId;
            this.authorName = authorName;
            this.query = query;
        }

        @NonNull public List<FeedRepository.Post> getItems() { return items; }

        /** First page in flight and nothing to show yet. */
        public boolean isLoading() { return loading; }

        public boolean isRefreshing() { return refreshing; }

        public boolean isLoadingMore() { return loadingMore; }

        /** The first page failed; the list is empty. */
        public boolean isError() { return error; }

        /** The last page-load failed; auto-loading stops until an explicit retry. */
        public boolean isLoadMoreError() { return loadMoreError; }

        public boolean hasMore() { return nextCursor != null; }

        @Nullable String getNextCursor() { return nextCursor; }

        @Nullable public String getAuthorId() { return authorId; }

        @Nullable public String getAuthorName() { return authorName; }

        @NonNull public String getQuery() { return query; }

        public boolean hasActiveFilter() {
            return (authorId != null && !authorId.isBlank()) || !query.isBlank();
        }

        boolean isBusy() { return loading || refreshing || loadingMore; }

        UiState withItems(List<FeedRepository.Post> next, @Nullable String cursor) {
            return new UiState(next, false, false, false, false, false,
                    cursor, authorId, authorName, query);
        }
    }

    private final FeedRepository repository;
    private final FeedSyncBus syncBus;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final CompositeDisposable loadDisposables = new CompositeDisposable();
    private final Set<String> reactionsInFlight = new HashSet<>();
    private final MutableLiveData<UiState> uiState =
            new MutableLiveData<>(new UiState(
                    List.of(), true, false, false, false, false, null, null, null, ""));
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();

    @VisibleForTesting
    public FeedViewModel(@NonNull FeedRepository repository) {
        this(repository, new FeedSyncBus());
    }

    @Inject
    public FeedViewModel(@NonNull FeedRepository repository, @NonNull FeedSyncBus syncBus) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.syncBus = Objects.requireNonNull(syncBus, "syncBus");
        disposables.add(syncBus.observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSyncEvent, ignored -> { }));
        loadFirstPage(false);
    }

    @NonNull public LiveData<UiState> getUiState() {
        return uiState;
    }

    @NonNull public LiveData<Outcome> getOutcome() {
        return outcome;
    }

    public void onOutcomeShown() {
        outcome.setValue(null);
    }

    public void setAuthorFilter(@Nullable String authorId, @Nullable String authorName) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        String normalizedId = FeedRules.normalize(authorId);
        String normalizedName = FeedRules.normalize(authorName);
        if (Objects.equals(normalizedId, state.authorId)) {
            return;
        }
        loadDisposables.clear();
        uiState.setValue(new UiState(
                List.of(), true, false, false, false, false, null,
                normalizedId, normalizedName, state.query));
        loadFirstPage(false);
    }

    public void setSearchQuery(@Nullable String rawQuery) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        String normalized = FeedRules.normalizeSearchQuery(rawQuery);
        String nextQuery = normalized == null ? "" : normalized;
        if (nextQuery.equals(state.query)) {
            return;
        }
        loadDisposables.clear();
        uiState.setValue(new UiState(
                List.of(), true, false, false, false, false, null,
                state.authorId, state.authorName, nextQuery));
        loadFirstPage(false);
    }

    /** Pull-to-refresh: keeps the current rows on screen until the new page arrives. */
    public void refresh() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy()) {
            return;
        }
        uiState.setValue(new UiState(
                state.items, false, true, false, false, false, state.nextCursor,
                state.authorId, state.authorName, state.query));
        loadFirstPage(true);
    }

    /** Retry after the first page failed. */
    public void retry() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy()) {
            return;
        }
        uiState.setValue(new UiState(
                List.of(), true, false, false, false, false, null,
                state.authorId, state.authorName, state.query));
        loadFirstPage(false);
    }

    public void loadMore() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy() || state.getNextCursor() == null) {
            return;
        }
        // A failed page stays failed until the user explicitly retries: silent
        // auto-redispatch on every scroll event used to loop the failing request.
        if (state.isLoadMoreError()) {
            return;
        }
        uiState.setValue(new UiState(
                state.items, false, false, true, false, false, state.nextCursor,
                state.authorId, state.authorName, state.query));
        loadPage(state.nextCursor);
    }

    /** Retry after the last page-load failed; asks for the same cursor again. */
    public void retryLoadMore() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy() || state.getNextCursor() == null) {
            return;
        }
        uiState.setValue(new UiState(
                state.items, false, false, true, false, false, state.nextCursor,
                state.authorId, state.authorName, state.query));
        loadPage(state.getNextCursor());
    }

    /** Removes one of the caller's own posts, restoring it and notifying if the server refuses. */
    public void deletePost(@NonNull String postId) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        uiState.setValue(state.withItems(remove(state.items, postId), state.nextCursor));
        disposables.add(repository.deletePost(postId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> outcome.setValue(Outcome.POST_DELETED),
                        error -> {
                            outcome.setValue(Outcome.POST_DELETE_FAILED);
                            refresh();
                        }));
    }

    /**
     * Flips the caller's reaction at once, then adopts the server tally. A tap on a post whose
     * previous reaction request is still in flight is ignored.
     */
    public void toggleReaction(@NonNull String postId, @NonNull String emoji) {
        UiState state = uiState.getValue();
        if (state == null || reactionsInFlight.contains(postId)) {
            return;
        }
        FeedRepository.Post post = find(state.items, postId);
        if (post == null) {
            return;
        }
        boolean active = !FeedRules.hasReaction(post.reactions(), emoji);
        reactionsInFlight.add(postId);
        uiState.setValue(state.withItems(
                replace(state.items, postId, withReactions(
                        post, FeedRules.toggle(post.reactions(), emoji, active))),
                state.nextCursor));
        disposables.add(repository.setReaction(postId, emoji, active)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        reactions -> {
                            reactionsInFlight.remove(postId);
                            replaceReactions(postId, reactions);
                        },
                        error -> {
                            reactionsInFlight.remove(postId);
                            replaceReactions(postId, post.reactions());
                        }));
    }

    private void onSyncEvent(@NonNull FeedSyncBus.Event event) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        if (event instanceof FeedSyncBus.PostUpdated updated) {
            if (find(state.items, updated.post().id()) != null) {
                uiState.setValue(state.withItems(
                        replace(state.items, updated.post().id(), updated.post()),
                        state.nextCursor));
            }
        } else if (event instanceof FeedSyncBus.PostDeleted deleted) {
            if (find(state.items, deleted.postId()) != null) {
                uiState.setValue(state.withItems(
                        remove(state.items, deleted.postId()),
                        state.nextCursor));
            }
        }
    }

    private void loadFirstPage(boolean keepRows) {
        UiState current = uiState.getValue();
        String authorId = current == null ? null : current.authorId;
        String query = current == null ? null : FeedRules.normalizeSearchQuery(current.query);
        loadDisposables.add(repository.listPosts(FeedRules.POST_PAGE_SIZE, null, authorId, query)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    UiState latest = uiState.getValue();
                    uiState.setValue(new UiState(
                            page.items(), false, false, false, false, false, page.nextCursor(),
                            latest == null ? authorId : latest.authorId,
                            latest == null ? null : latest.authorName,
                            latest == null ? "" : latest.query));
                }, error -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            keepRows && state != null ? state.items : List.of(),
                            false, false, false, false, !keepRows,
                            keepRows && state != null ? state.nextCursor : null,
                            state == null ? authorId : state.authorId,
                            state == null ? null : state.authorName,
                            state == null ? "" : state.query));
                }));
    }

    private void loadPage(@Nullable String cursor) {
        UiState current = uiState.getValue();
        String authorId = current == null ? null : current.authorId;
        String query = current == null ? null : FeedRules.normalizeSearchQuery(current.query);
        loadDisposables.add(repository.listPosts(FeedRules.POST_PAGE_SIZE, cursor, authorId, query)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    UiState state = uiState.getValue();
                    List<FeedRepository.Post> merged = new ArrayList<>();
                    if (state != null) {
                        merged.addAll(state.items);
                    }
                    merged.addAll(page.items());
                    uiState.setValue(new UiState(
                            List.copyOf(merged), false, false, false, false, false, page.nextCursor(),
                            state == null ? authorId : state.authorId,
                            state == null ? null : state.authorName,
                            state == null ? "" : state.query));
                }, error -> {
                    UiState state = uiState.getValue();
                    // Keep the rows and the cursor so an explicit retry can resume the
                    // same page, but surface the failure instead of swallowing it.
                    uiState.setValue(new UiState(
                            state == null ? List.of() : state.items,
                            false, false, false, true, false,
                            state == null ? null : state.nextCursor,
                            state == null ? authorId : state.authorId,
                            state == null ? null : state.authorName,
                            state == null ? "" : state.query));
                }));
    }

    private void replaceReactions(
            @NonNull String postId, @NonNull List<FeedRepository.Reaction> reactions) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        FeedRepository.Post post = find(state.items, postId);
        if (post == null) {
            return;
        }
        uiState.setValue(state.withItems(
                replace(state.items, postId, withReactions(post, reactions)), state.nextCursor));
    }

    private static FeedRepository.Post withReactions(
            FeedRepository.Post post, List<FeedRepository.Reaction> reactions) {
        return new FeedRepository.Post(
                post.id(), post.author(), post.body(), post.image(), post.video(),
                post.createdAt(), post.editedAt(), post.commentCount(), reactions, post.mine());
    }

    private static List<FeedRepository.Post> replace(
            List<FeedRepository.Post> items,
            String postId,
            FeedRepository.Post replacement) {
        List<FeedRepository.Post> next = new ArrayList<>(items.size());
        for (FeedRepository.Post post : items) {
            next.add(postId.equals(post.id()) ? replacement : post);
        }
        return List.copyOf(next);
    }

    private static List<FeedRepository.Post> remove(
            List<FeedRepository.Post> items, String postId) {
        List<FeedRepository.Post> remaining = new ArrayList<>(items.size());
        for (FeedRepository.Post post : items) {
            if (!postId.equals(post.id())) {
                remaining.add(post);
            }
        }
        return List.copyOf(remaining);
    }

    @Nullable
    private static FeedRepository.Post find(List<FeedRepository.Post> items, String postId) {
        for (FeedRepository.Post post : items) {
            if (postId.equals(post.id())) {
                return post;
            }
        }
        return null;
    }

    @Override
    protected void onCleared() {
        loadDisposables.clear();
        disposables.clear();
        super.onCleared();
    }
}
