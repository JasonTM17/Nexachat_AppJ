package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

/** The public feed list: newest-first pages, pull-to-refresh, reactions and own-post deletion. */
@HiltViewModel
public final class FeedViewModel extends ViewModel {

    /** One immutable snapshot of the list. */
    public static final class UiState {
        private final List<FeedRepository.Post> items;
        private final boolean loading;
        private final boolean refreshing;
        private final boolean loadingMore;
        private final boolean error;
        @Nullable private final String nextCursor;

        UiState(
                List<FeedRepository.Post> items,
                boolean loading,
                boolean refreshing,
                boolean loadingMore,
                boolean error,
                @Nullable String nextCursor) {
            this.items = items;
            this.loading = loading;
            this.refreshing = refreshing;
            this.loadingMore = loadingMore;
            this.error = error;
            this.nextCursor = nextCursor;
        }

        @NonNull public List<FeedRepository.Post> getItems() { return items; }

        /** First page in flight and nothing to show yet. */
        public boolean isLoading() { return loading; }

        public boolean isRefreshing() { return refreshing; }

        public boolean isLoadingMore() { return loadingMore; }

        /** The first page failed; the list is empty. */
        public boolean isError() { return error; }

        public boolean hasMore() { return nextCursor != null; }

        @Nullable String getNextCursor() { return nextCursor; }

        boolean isBusy() { return loading || refreshing || loadingMore; }

        UiState withItems(List<FeedRepository.Post> next, @Nullable String cursor) {
            return new UiState(next, false, false, false, false, cursor);
        }
    }

    private final FeedRepository repository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Set<String> reactionsInFlight = new HashSet<>();
    private final MutableLiveData<UiState> uiState =
            new MutableLiveData<>(new UiState(List.of(), true, false, false, false, null));

    @Inject
    public FeedViewModel(@NonNull FeedRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        loadFirstPage(false);
    }

    @NonNull public LiveData<UiState> getUiState() {
        return uiState;
    }

    /** Pull-to-refresh: keeps the current rows on screen until the new page arrives. */
    public void refresh() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy()) {
            return;
        }
        uiState.setValue(new UiState(
                state.items, false, true, false, false, state.nextCursor));
        loadFirstPage(true);
    }

    /** Retry after the first page failed. */
    public void retry() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy()) {
            return;
        }
        uiState.setValue(new UiState(List.of(), true, false, false, false, null));
        loadFirstPage(false);
    }

    public void loadMore() {
        UiState state = uiState.getValue();
        if (state == null || state.isBusy() || state.nextCursor == null) {
            return;
        }
        uiState.setValue(new UiState(
                state.items, false, false, true, false, state.nextCursor));
        loadPage(state.nextCursor);
    }

    /** Removes one of the caller's own posts, restoring it when the server refuses. */
    public void deletePost(@NonNull String postId) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        List<FeedRepository.Post> remaining = new ArrayList<>(state.items.size());
        for (FeedRepository.Post post : state.items) {
            if (!postId.equals(post.id())) {
                remaining.add(post);
            }
        }
        uiState.setValue(state.withItems(List.copyOf(remaining), state.nextCursor));
        disposables.add(repository.deletePost(postId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, error -> refresh()));
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

    private void loadFirstPage(boolean keepRows) {
        disposables.add(repository.listPosts(FeedRules.POST_PAGE_SIZE, null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> uiState.setValue(new UiState(
                                page.items(), false, false, false, false, page.nextCursor())),
                        error -> {
                            UiState state = uiState.getValue();
                            uiState.setValue(new UiState(
                                    keepRows && state != null ? state.items : List.of(),
                                    false, false, false, !keepRows,
                                    keepRows && state != null ? state.nextCursor : null));
                        }));
    }

    private void loadPage(@Nullable String cursor) {
        disposables.add(repository.listPosts(FeedRules.POST_PAGE_SIZE, cursor)
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
                            List.copyOf(merged), false, false, false, false, page.nextCursor()));
                }, error -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            state == null ? List.of() : state.items,
                            false, false, false, false,
                            state == null ? null : state.nextCursor));
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
                post.id(), post.author(), post.body(), post.image(), post.createdAt(),
                post.commentCount(), reactions, post.mine());
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
        disposables.clear();
        super.onCleared();
    }
}
