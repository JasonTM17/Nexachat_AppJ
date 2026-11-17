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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Inject;

/** One post with its flat comments, plus the caller's own delete and reaction actions. */
@HiltViewModel
public final class PostDetailViewModel extends ViewModel {

    /** What the screen has to tell the user after an action. */
    public enum Outcome {
        COMMENT_SENT,
        COMMENT_FAILED,
        COMMENT_DELETED,
        COMMENT_DELETE_FAILED,
        POST_DELETED,
        POST_DELETE_FAILED
    }

    /** One immutable snapshot of the detail screen. */
    public static final class UiState {
        @Nullable private final FeedRepository.Post post;
        private final List<FeedRepository.Comment> comments;
        private final boolean loading;
        private final boolean error;
        private final boolean loadingMore;
        private final boolean sending;
        @Nullable private final String nextCursor;

        UiState(
                @Nullable FeedRepository.Post post,
                List<FeedRepository.Comment> comments,
                boolean loading,
                boolean error,
                boolean loadingMore,
                boolean sending,
                @Nullable String nextCursor) {
            this.post = post;
            this.comments = comments;
            this.loading = loading;
            this.error = error;
            this.loadingMore = loadingMore;
            this.sending = sending;
            this.nextCursor = nextCursor;
        }

        @Nullable public FeedRepository.Post getPost() { return post; }

        @NonNull public List<FeedRepository.Comment> getComments() { return comments; }

        public boolean isLoading() { return loading; }

        /** The post itself could not be loaded. */
        public boolean isError() { return error; }

        public boolean isLoadingMore() { return loadingMore; }

        public boolean isSending() { return sending; }

        public boolean hasMoreComments() { return nextCursor != null; }

        @Nullable String getNextCursor() { return nextCursor; }

        UiState withPost(@Nullable FeedRepository.Post next) {
            return new UiState(next, comments, loading, error, loadingMore, sending, nextCursor);
        }

        UiState withComments(
                List<FeedRepository.Comment> next, @Nullable String cursor, boolean busy) {
            return new UiState(post, next, loading, error, false, busy, cursor);
        }
    }

    private final FeedRepository repository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(
            new UiState(null, List.of(), true, false, false, false, null));
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();
    private String postId = "";
    private String clientCommentId = UUID.randomUUID().toString();

    @Inject
    public PostDetailViewModel(@NonNull FeedRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @NonNull public LiveData<UiState> getUiState() { return uiState; }

    /** One-shot result of the last action; consume with {@link #onOutcomeShown()}. */
    @NonNull public LiveData<Outcome> getOutcome() { return outcome; }

    /** Loads the post and its first comment page; safe to call again after a failure. */
    public void load(@NonNull String id) {
        if (!id.equals(postId)) {
            postId = id;
            uiState.setValue(new UiState(null, List.of(), true, false, false, false, null));
        }
        disposables.add(repository.getPost(postId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(post -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            post,
                            state == null ? List.of() : state.comments,
                            false, false, false, false,
                            state == null ? null : state.nextCursor));
                    loadComments(null);
                }, error -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            state == null ? null : state.post,
                            state == null ? List.of() : state.comments,
                            false, true, false, false, null));
                }));
    }

    public void loadMoreComments() {
        UiState state = uiState.getValue();
        if (state == null || state.loadingMore || state.nextCursor == null) {
            return;
        }
        uiState.setValue(new UiState(state.post, state.comments, state.loading, state.error,
                true, state.sending, state.nextCursor));
        loadComments(state.nextCursor);
    }

    public void sendComment(@Nullable String raw) {
        UiState state = uiState.getValue();
        if (state == null || state.sending || !FeedRules.canSubmitComment(raw)) {
            return;
        }
        String body = FeedRules.normalize(raw);
        if (body == null) {
            return;
        }
        uiState.setValue(new UiState(state.post, state.comments, state.loading, state.error,
                state.loadingMore, true, state.nextCursor));
        disposables.add(repository.addComment(postId, body, clientCommentId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(comment -> {
                    clientCommentId = UUID.randomUUID().toString();
                    UiState current = uiState.getValue();
                    List<FeedRepository.Comment> next = new ArrayList<>();
                    if (current != null) {
                        next.addAll(current.comments);
                    }
                    next.add(comment);
                    FeedRepository.Post post = current == null ? null : current.post;
                    uiState.setValue(new UiState(
                            post == null ? null : withCommentCount(post, post.commentCount() + 1),
                            List.copyOf(next),
                            false, false, false, false,
                            current == null ? null : current.nextCursor));
                    outcome.setValue(Outcome.COMMENT_SENT);
                }, error -> {
                    UiState current = uiState.getValue();
                    uiState.setValue(new UiState(
                            current == null ? null : current.post,
                            current == null ? List.of() : current.comments,
                            false, false, false, false,
                            current == null ? null : current.nextCursor));
                    outcome.setValue(Outcome.COMMENT_FAILED);
                }));
    }

    public void deleteComment(@NonNull String commentId) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        List<FeedRepository.Comment> remaining = new ArrayList<>(state.comments.size());
        for (FeedRepository.Comment comment : state.comments) {
            if (!commentId.equals(comment.id())) {
                remaining.add(comment);
            }
        }
        FeedRepository.Post post = state.post;
        uiState.setValue(new UiState(
                post == null ? null : withCommentCount(post, Math.max(0, post.commentCount() - 1)),
                List.copyOf(remaining),
                state.loading, state.error, state.loadingMore, state.sending, state.nextCursor));
        disposables.add(repository.deleteComment(commentId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> outcome.setValue(Outcome.COMMENT_DELETED),
                        error -> {
                            outcome.setValue(Outcome.COMMENT_DELETE_FAILED);
                            load(postId);
                        }));
    }

    public void deletePost() {
        UiState state = uiState.getValue();
        if (state == null || state.post == null) {
            return;
        }
        disposables.add(repository.deletePost(state.post.id())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> outcome.setValue(Outcome.POST_DELETED),
                        error -> outcome.setValue(Outcome.POST_DELETE_FAILED)));
    }

    /** Flips the caller's reaction at once, then adopts the server tally. */
    public void toggleReaction(@NonNull String emoji) {
        UiState state = uiState.getValue();
        FeedRepository.Post post = state == null ? null : state.post;
        if (post == null) {
            return;
        }
        boolean active = !FeedRules.hasReaction(post.reactions(), emoji);
        uiState.setValue(state.withPost(
                withReactions(post, FeedRules.toggle(post.reactions(), emoji, active))));
        disposables.add(repository.setReaction(post.id(), emoji, active)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(reactions -> {
                    UiState current = uiState.getValue();
                    if (current != null && current.post != null) {
                        uiState.setValue(current.withPost(
                                withReactions(current.post, reactions)));
                    }
                }, error -> {
                    UiState current = uiState.getValue();
                    if (current != null && current.post != null) {
                        uiState.setValue(current.withPost(
                                withReactions(current.post, post.reactions())));
                    }
                }));
    }

    public void onOutcomeShown() {
        outcome.setValue(null);
    }

    private void loadComments(@Nullable String cursor) {
        disposables.add(repository.listComments(postId, FeedRules.COMMENT_PAGE_SIZE, cursor)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    UiState state = uiState.getValue();
                    List<FeedRepository.Comment> merged = new ArrayList<>();
                    if (cursor != null && state != null) {
                        merged.addAll(state.comments);
                    }
                    merged.addAll(page.items());
                    uiState.setValue(new UiState(
                            state == null ? null : state.post,
                            List.copyOf(merged),
                            false, false, false, false,
                            page.nextCursor()));
                }, error -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            state == null ? null : state.post,
                            state == null ? List.of() : state.comments,
                            false, cursor == null && (state == null || state.post == null),
                            false, false,
                            state == null ? null : state.nextCursor));
                }));
    }

    private static FeedRepository.Post withCommentCount(
            FeedRepository.Post post, int commentCount) {
        return new FeedRepository.Post(
                post.id(), post.author(), post.body(), post.image(), post.createdAt(),
                commentCount, post.reactions(), post.mine());
    }

    private static FeedRepository.Post withReactions(
            FeedRepository.Post post, List<FeedRepository.Reaction> reactions) {
        return new FeedRepository.Post(
                post.id(), post.author(), post.body(), post.image(), post.createdAt(),
                post.commentCount(), reactions, post.mine());
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
