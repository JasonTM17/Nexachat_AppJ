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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Inject;

/**
 * One post with its flat comments, plus reaction, comment add/edit/delete,
 * post delete, and live sync back to {@link FeedViewModel}.
 */
@HiltViewModel
public final class PostDetailViewModel extends ViewModel {

    /** What the screen has to tell the user after an action. */
    public enum Outcome {
        COMMENT_SENT,
        COMMENT_FAILED,
        COMMENT_RATE_LIMITED,
        COMMENT_EDITED,
        COMMENT_EDIT_FAILED,
        COMMENT_EDIT_RATE_LIMITED,
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
        private final boolean commentsError;
        @Nullable private final FeedRepository.Comment editingComment;

        UiState(
                @Nullable FeedRepository.Post post,
                List<FeedRepository.Comment> comments,
                boolean loading,
                boolean error,
                boolean loadingMore,
                boolean sending,
                @Nullable String nextCursor,
                boolean commentsError) {
            this(post, comments, loading, error, loadingMore, sending,
                    nextCursor, commentsError, null);
        }

        UiState(
                @Nullable FeedRepository.Post post,
                List<FeedRepository.Comment> comments,
                boolean loading,
                boolean error,
                boolean loadingMore,
                boolean sending,
                @Nullable String nextCursor,
                boolean commentsError,
                @Nullable FeedRepository.Comment editingComment) {
            this.post = post;
            this.comments = comments;
            this.loading = loading;
            this.error = error;
            this.loadingMore = loadingMore;
            this.sending = sending;
            this.nextCursor = nextCursor;
            this.commentsError = commentsError;
            this.editingComment = editingComment;
        }

        @Nullable public FeedRepository.Post getPost() { return post; }

        @NonNull public List<FeedRepository.Comment> getComments() { return comments; }

        public boolean isLoading() { return loading; }

        /** The post itself could not be loaded. */
        public boolean isError() { return error; }

        public boolean isLoadingMore() { return loadingMore; }

        public boolean isSending() { return sending; }

        public boolean hasMoreComments() { return nextCursor != null; }

        /** A comment page failed after the post is on screen. */
        public boolean isCommentsError() { return commentsError; }

        @Nullable public FeedRepository.Comment getEditingComment() { return editingComment; }

        @Nullable String getNextCursor() { return nextCursor; }

        UiState withPost(@Nullable FeedRepository.Post next) {
            return new UiState(next, comments, loading, error, loadingMore, sending,
                    nextCursor, commentsError, editingComment);
        }

        UiState withEditingComment(@Nullable FeedRepository.Comment target) {
            return new UiState(post, comments, loading, error, loadingMore, sending,
                    nextCursor, commentsError, target);
        }

        UiState withComments(
                List<FeedRepository.Comment> next, @Nullable String cursor, boolean busy) {
            return new UiState(post, next, loading, error, false, busy,
                    cursor, false, editingComment);
        }
    }

    private final FeedRepository repository;
    private final FeedSyncBus syncBus;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(
            new UiState(null, List.of(), true, false, false, false, null, false, null));
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();
    private String postId = "";
    private String clientCommentId = UUID.randomUUID().toString();
    private boolean reactionInFlight;

    @VisibleForTesting
    public PostDetailViewModel(@NonNull FeedRepository repository) {
        this(repository, new FeedSyncBus());
    }

    @Inject
    public PostDetailViewModel(@NonNull FeedRepository repository, @NonNull FeedSyncBus syncBus) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.syncBus = Objects.requireNonNull(syncBus, "syncBus");
        disposables.add(syncBus.observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSyncEvent, ignored -> { }));
    }

    @NonNull public LiveData<UiState> getUiState() { return uiState; }

    /** One-shot result of the last action; consume with {@link #onOutcomeShown()}. */
    @NonNull public LiveData<Outcome> getOutcome() { return outcome; }

    /** Loads the post and its first comment page; safe to call again after a failure. */
    public void load(@NonNull String id) {
        if (!id.equals(postId)) {
            postId = id;
            uiState.setValue(new UiState(
                    null, List.of(), true, false, false, false, null, false, null));
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
                            state == null ? null : state.nextCursor,
                            false,
                            state == null ? null : state.editingComment));
                    syncBus.publish(new FeedSyncBus.PostUpdated(post));
                    loadComments(null);
                }, error -> {
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            state == null ? null : state.post,
                            state == null ? List.of() : state.comments,
                            false, true, false, false, null, false, null));
                }));
    }

    public void loadMoreComments() {
        UiState state = uiState.getValue();
        if (state == null || state.loadingMore || state.getNextCursor() == null) {
            return;
        }
        uiState.setValue(new UiState(state.post, state.comments, state.loading, state.error,
                true, state.sending, state.nextCursor, false, state.editingComment));
        loadComments(state.nextCursor);
    }

    /** Retries the first comment page after it failed; keeps the loaded post. */
    public void retryComments() {
        UiState state = uiState.getValue();
        if (state == null || state.loading || state.loadingMore || state.getPost() == null) {
            return;
        }
        uiState.setValue(new UiState(state.post, state.comments, false, false, true, false,
                null, false, state.editingComment));
        loadComments(null);
    }

    public void startEditComment(@NonNull FeedRepository.Comment comment) {
        UiState state = uiState.getValue();
        if (state == null || !comment.mine()) {
            return;
        }
        uiState.setValue(state.withEditingComment(comment));
    }

    public void cancelEditComment() {
        UiState state = uiState.getValue();
        if (state == null || state.editingComment == null) {
            return;
        }
        uiState.setValue(state.withEditingComment(null));
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
        if (state.editingComment != null) {
            saveEditedComment(state, state.editingComment, body);
            return;
        }
        uiState.setValue(new UiState(state.post, state.comments, state.loading, state.error,
                state.loadingMore, true, state.nextCursor, state.commentsError, null));
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
                    FeedRepository.Post updatedPost = post == null
                            ? null
                            : withCommentCount(post, post.commentCount() + 1);
                    uiState.setValue(new UiState(
                            updatedPost,
                            List.copyOf(next),
                            false, false, false, false,
                            current == null ? null : current.nextCursor,
                            current != null && current.commentsError,
                            null));
                    if (updatedPost != null) {
                        syncBus.publish(new FeedSyncBus.PostUpdated(updatedPost));
                    }
                    outcome.setValue(Outcome.COMMENT_SENT);
                }, error -> {
                    UiState current = uiState.getValue();
                    uiState.setValue(new UiState(
                            current == null ? null : current.post,
                            current == null ? List.of() : current.comments,
                            false, false, false, false,
                            current == null ? null : current.nextCursor,
                            current != null && current.commentsError,
                            null));
                    outcome.setValue(FeedRules.isRateLimited(error)
                            ? Outcome.COMMENT_RATE_LIMITED
                            : Outcome.COMMENT_FAILED);
                }));
    }

    private void saveEditedComment(
            @NonNull UiState state,
            @NonNull FeedRepository.Comment target,
            @NonNull String body) {
        uiState.setValue(new UiState(state.post, state.comments, state.loading, state.error,
                state.loadingMore, true, state.nextCursor, state.commentsError, target));
        disposables.add(repository.editComment(target.id(), body)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(edited -> {
                    UiState current = uiState.getValue();
                    List<FeedRepository.Comment> next = new ArrayList<>();
                    if (current != null) {
                        for (FeedRepository.Comment row : current.comments) {
                            next.add(target.id().equals(row.id()) ? edited : row);
                        }
                    }
                    uiState.setValue(new UiState(
                            current == null ? null : current.post,
                            List.copyOf(next),
                            false, false, false, false,
                            current == null ? null : current.nextCursor,
                            current != null && current.commentsError,
                            null));
                    outcome.setValue(Outcome.COMMENT_EDITED);
                }, error -> {
                    UiState current = uiState.getValue();
                    uiState.setValue(new UiState(
                            current == null ? null : current.post,
                            current == null ? List.of() : current.comments,
                            false, false, false, false,
                            current == null ? null : current.nextCursor,
                            current != null && current.commentsError,
                            current == null ? target : current.editingComment));
                    outcome.setValue(FeedRules.isRateLimited(error)
                            ? Outcome.COMMENT_EDIT_RATE_LIMITED
                            : Outcome.COMMENT_EDIT_FAILED);
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
        FeedRepository.Comment nextEditing =
                state.editingComment != null && commentId.equals(state.editingComment.id())
                        ? null
                        : state.editingComment;
        FeedRepository.Post post = state.post;
        FeedRepository.Post updatedPost = post == null
                ? null
                : withCommentCount(post, Math.max(0, post.commentCount() - 1));
        uiState.setValue(new UiState(
                updatedPost,
                List.copyOf(remaining),
                state.loading, state.error, state.loadingMore, state.sending, state.nextCursor,
                state.commentsError, nextEditing));
        disposables.add(repository.deleteComment(commentId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            if (updatedPost != null) {
                                syncBus.publish(new FeedSyncBus.PostUpdated(updatedPost));
                            }
                            outcome.setValue(Outcome.COMMENT_DELETED);
                        },
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
        String deletedPostId = state.post.id();
        disposables.add(repository.deletePost(deletedPostId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            syncBus.publish(new FeedSyncBus.PostDeleted(deletedPostId));
                            outcome.setValue(Outcome.POST_DELETED);
                        },
                        error -> outcome.setValue(Outcome.POST_DELETE_FAILED)));
    }

    /**
     * Flips the caller's reaction at once, then adopts the server tally. A tap while the previous
     * reaction request is still in flight is ignored.
     */
    public void toggleReaction(@NonNull String emoji) {
        UiState state = uiState.getValue();
        FeedRepository.Post post = state == null ? null : state.post;
        if (post == null || reactionInFlight) {
            return;
        }
        boolean active = !FeedRules.hasReaction(post.reactions(), emoji);
        reactionInFlight = true;
        FeedRepository.Post optimistic = withReactions(
                post, FeedRules.toggle(post.reactions(), emoji, active));
        uiState.setValue(state.withPost(optimistic));
        disposables.add(repository.setReaction(post.id(), emoji, active)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(reactions -> {
                    reactionInFlight = false;
                    UiState current = uiState.getValue();
                    if (current != null && current.post != null) {
                        FeedRepository.Post updated = withReactions(current.post, reactions);
                        uiState.setValue(current.withPost(updated));
                        syncBus.publish(new FeedSyncBus.PostUpdated(updated));
                    }
                }, error -> {
                    reactionInFlight = false;
                    UiState current = uiState.getValue();
                    if (current != null && current.post != null) {
                        FeedRepository.Post restored = withReactions(current.post, post.reactions());
                        uiState.setValue(current.withPost(restored));
                    }
                }));
    }

    public void onOutcomeShown() {
        outcome.setValue(null);
    }

    private void onSyncEvent(@NonNull FeedSyncBus.Event event) {
        UiState state = uiState.getValue();
        if (state == null || state.post == null) {
            return;
        }
        if (event instanceof FeedSyncBus.PostUpdated updated
                && state.post.id().equals(updated.post().id())
                && !state.post.equals(updated.post())) {
            uiState.setValue(state.withPost(updated.post()));
        }
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
                            page.nextCursor(),
                            false,
                            state == null ? null : state.editingComment));
                }, error -> {
                    UiState state = uiState.getValue();
                    // The post survived, so its error view must not swallow the comment
                    // failure: flag it separately or the empty CTA lies about a loaded list.
                    boolean postOnScreen = state != null && state.post != null;
                    uiState.setValue(new UiState(
                            state == null ? null : state.post,
                            state == null ? List.of() : state.comments,
                            false,
                            !postOnScreen && cursor == null,
                            false, false,
                            state == null ? null : state.nextCursor,
                            postOnScreen,
                            state == null ? null : state.editingComment));
                }));
    }

    private static FeedRepository.Post withCommentCount(
            FeedRepository.Post post, int commentCount) {
        return new FeedRepository.Post(
                post.id(), post.author(), post.body(), post.image(), post.video(),
                post.createdAt(), post.editedAt(), commentCount, post.reactions(), post.mine());
    }

    private static FeedRepository.Post withReactions(
            FeedRepository.Post post, List<FeedRepository.Reaction> reactions) {
        return new FeedRepository.Post(
                post.id(), post.author(), post.body(), post.image(), post.video(),
                post.createdAt(), post.editedAt(), post.commentCount(), reactions, post.mine());
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
