package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class PostDetailViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeFeedRepository repository;
    private PostDetailViewModel viewModel;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeFeedRepository();
        viewModel = new PostDetailViewModel(repository);
    }

    @After public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test public void loadFetchesThePostThenTheFirstCommentPage() {
        viewModel.load("post-1");

        assertTrue(state().isLoading());
        assertEquals(1, repository.getPostCalls.size());

        repository.getPostCalls.get(0).onSuccess(post("post-1", 2));

        assertEquals("post-1", state().getPost().id());
        assertFalse(state().isLoading());
        assertEquals(1, repository.listCommentsCalls.size());
        assertNull(repository.listCommentsCursors.get(0));

        repository.listCommentsCalls.get(0).onSuccess(
                new FeedRepository.CommentPage(List.of(comment("comment-1")), "cursor-1"));

        assertEquals(List.of("comment-1"), commentIds(state().getComments()));
        assertTrue(state().hasMoreComments());
    }

    @Test public void aFailedPostLoadShowsTheErrorState() {
        viewModel.load("post-1");

        repository.getPostCalls.get(0).onError(new FeedApiException(404, "POST_NOT_FOUND"));

        assertTrue(state().isError());
        assertNull(state().getPost());
        assertTrue(repository.listCommentsCalls.isEmpty());
    }

    @Test public void loadMoreCommentsAppendsTheNextPage() {
        loadPostWithComments("cursor-1");

        viewModel.loadMoreComments();

        assertEquals("cursor-1", repository.listCommentsCursors.get(1));
        assertTrue(state().isLoadingMore());

        repository.listCommentsCalls.get(1).onSuccess(
                new FeedRepository.CommentPage(List.of(comment("comment-2")), null));

        assertEquals(List.of("comment-1", "comment-2"), commentIds(state().getComments()));
        assertFalse(state().hasMoreComments());
    }

    @Test public void loadMoreCommentsWithoutACursorDoesNothing() {
        loadPostWithComments(null);

        viewModel.loadMoreComments();

        assertEquals(1, repository.listCommentsCalls.size());
    }

    @Test public void aBlankCommentIsNeverSent() {
        loadPostWithComments(null);

        viewModel.sendComment("   ");

        assertTrue(repository.addCommentCalls.isEmpty());
    }

    @Test public void anOverLongCommentIsNeverSent() {
        loadPostWithComments(null);

        viewModel.sendComment("a".repeat(FeedRules.MAX_COMMENT_LENGTH + 1));

        assertTrue(repository.addCommentCalls.isEmpty());
    }

    @Test public void aSentCommentIsAppendedAndCounted() {
        loadPostWithComments(null);

        viewModel.sendComment("  hello  ");

        assertTrue(state().isSending());
        assertEquals("hello", repository.addCommentBodies.get(0));

        repository.addCommentCalls.get(0).onSuccess(comment("comment-2"));

        assertEquals(List.of("comment-1", "comment-2"), commentIds(state().getComments()));
        assertEquals(2, state().getPost().commentCount());
        assertEquals(PostDetailViewModel.Outcome.COMMENT_SENT, outcome());
        assertFalse(state().isSending());
    }

    @Test public void aFailedCommentReportsFailureAndClearsSending() {
        loadPostWithComments(null);

        viewModel.sendComment("hello");
        repository.addCommentCalls.get(0).onError(new IllegalStateException("offline"));

        assertEquals(PostDetailViewModel.Outcome.COMMENT_FAILED, outcome());
        assertFalse(state().isSending());
        assertEquals(List.of("comment-1"), commentIds(state().getComments()));
    }

    @Test public void aRateLimitedCommentIsReportedSeparately() {
        loadPostWithComments(null);

        viewModel.sendComment("hello");
        repository.addCommentCalls.get(0).onError(new FeedApiException(429, null));

        assertEquals("COMMENT_RATE_LIMITED", outcome().name());
        assertFalse(state().isSending());
        assertEquals(List.of("comment-1"), commentIds(state().getComments()));
    }

    @Test public void aRateLimitedCommentKeepsItsClientCommentIdForTheRetry() {
        loadPostWithComments(null);

        viewModel.sendComment("hello");
        repository.addCommentCalls.get(0).onError(new FeedApiException(429, null));
        viewModel.sendComment("hello");

        assertEquals(repository.addCommentClientIds.get(0), repository.addCommentClientIds.get(1));
    }

    @Test public void theClientCommentIdChangesAfterASuccessfulSend() {
        loadPostWithComments(null);

        viewModel.sendComment("first");
        repository.addCommentCalls.get(0).onSuccess(comment("comment-2"));
        viewModel.sendComment("second");

        assertEquals(2, repository.addCommentClientIds.size());
        assertFalse(repository.addCommentClientIds.get(0)
                .equals(repository.addCommentClientIds.get(1)));
    }

    @Test public void theClientCommentIdSurvivesARetry() {
        loadPostWithComments(null);

        viewModel.sendComment("hello");
        repository.addCommentCalls.get(0).onError(new IllegalStateException("offline"));
        viewModel.sendComment("hello");

        assertEquals(repository.addCommentClientIds.get(0), repository.addCommentClientIds.get(1));
    }

    @Test public void deletingACommentRemovesItAndDecrementsTheCount() {
        loadPostWithComments(null);

        viewModel.deleteComment("comment-1");

        assertTrue(state().getComments().isEmpty());
        assertEquals(0, state().getPost().commentCount());
        assertEquals(1, repository.deleteCommentCalls.size());
    }

    @Test public void aRefusedCommentDeleteReloadsThePost() {
        loadPostWithComments(null);

        viewModel.deleteComment("comment-1");
        repository.deleteCommentCalls.get(0).onError(new IllegalStateException("forbidden"));

        assertEquals(PostDetailViewModel.Outcome.COMMENT_DELETE_FAILED, outcome());
        assertEquals(2, repository.getPostCalls.size());
    }

    @Test public void deletingThePostReportsSuccess() {
        loadPostWithComments(null);

        viewModel.deletePost();
        repository.deletePostCalls.get(0).onComplete();

        assertEquals(PostDetailViewModel.Outcome.POST_DELETED, outcome());
    }

    @Test public void aRefusedPostDeleteReportsFailure() {
        loadPostWithComments(null);

        viewModel.deletePost();
        repository.deletePostCalls.get(0).onError(new IllegalStateException("forbidden"));

        assertEquals(PostDetailViewModel.Outcome.POST_DELETE_FAILED, outcome());
    }

    @Test public void aReactionFlipsBeforeTheServerAnswers() {
        loadPostWithComments(null);

        viewModel.toggleReaction("\uD83D\uDC4D");

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)),
                state().getPost().reactions());
        assertTrue(repository.setReactionActive.get(0));
    }

    @Test public void theServerTallyReplacesTheOptimisticOne() {
        loadPostWithComments(null);
        viewModel.toggleReaction("\uD83D\uDC4D");

        repository.setReactionCalls.get(0).onSuccess(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 7, true)));

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 7, true)),
                state().getPost().reactions());
    }

    @Test public void aFailedReactionRestoresThePreviousTally() {
        loadPostWithComments(null);
        viewModel.toggleReaction("\uD83D\uDC4D");

        repository.setReactionCalls.get(0).onError(new IllegalStateException("offline"));

        assertTrue(state().getPost().reactions().isEmpty());
    }

    @Test public void aSecondTapWhileOneIsInFlightIsIgnored() {
        loadPostWithComments(null);

        viewModel.toggleReaction("\uD83D\uDC4D");
        viewModel.toggleReaction("\uD83D\uDC4D");

        assertEquals(1, repository.setReactionCalls.size());
        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)),
                state().getPost().reactions());

        repository.setReactionCalls.get(0).onSuccess(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)));
        viewModel.toggleReaction("\uD83D\uDC4D");

        assertEquals(2, repository.setReactionCalls.size());
        assertFalse(repository.setReactionActive.get(1));
    }

    @Test public void aFailedReactionAllowsTheNextTap() {
        loadPostWithComments(null);
        viewModel.toggleReaction("\uD83D\uDC4D");
        repository.setReactionCalls.get(0).onError(new IllegalStateException("offline"));

        viewModel.toggleReaction("\uD83D\uDC4D");

        assertEquals(2, repository.setReactionCalls.size());
        assertTrue(repository.setReactionActive.get(1));
    }

    @Test public void aReactionWithoutAPostDoesNothing() {
        viewModel.toggleReaction("\uD83D\uDC4D");

        assertTrue(repository.setReactionCalls.isEmpty());
    }

    private void loadPostWithComments(String cursor) {
        viewModel.load("post-1");
        repository.getPostCalls.get(0).onSuccess(post("post-1", 1));
        repository.listCommentsCalls.get(0).onSuccess(
                new FeedRepository.CommentPage(List.of(comment("comment-1")), cursor));
    }

    private PostDetailViewModel.UiState state() {
        return viewModel.getUiState().getValue();
    }

    private PostDetailViewModel.Outcome outcome() {
        return viewModel.getOutcome().getValue();
    }

    private static List<String> commentIds(List<FeedRepository.Comment> comments) {
        return comments.stream().map(FeedRepository.Comment::id).toList();
    }

    private static FeedRepository.Post post(String id, int commentCount) {
        return new FeedRepository.Post(
                id,
                new FeedRepository.Author("author-1", "An", null),
                "body",
                null,
                "2026-02-25T05:33:20Z",
                commentCount,
                List.of(),
                false);
    }

    private static FeedRepository.Comment comment(String id) {
        return new FeedRepository.Comment(
                id,
                "post-1",
                new FeedRepository.Author("author-2", "Binh", null),
                "hello",
                "2026-02-25T05:34:20Z",
                false);
    }
}
