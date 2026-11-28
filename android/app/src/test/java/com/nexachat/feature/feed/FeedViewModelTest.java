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

public final class FeedViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeFeedRepository repository;
    private FeedSyncBus syncBus;
    private FeedViewModel viewModel;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeFeedRepository();
        syncBus = new FeedSyncBus();
        viewModel = new FeedViewModel(repository, syncBus);
    }

    @After public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test public void theFirstPageLoadsOnConstruction() {
        assertEquals(1, repository.listPostsCalls.size());
        assertNull(repository.listPostsCursors.get(0));
        assertTrue(state().isLoading());

        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        assertEquals(List.of("post-1"), ids(state().getItems()));
        assertFalse(state().isLoading());
        assertFalse(state().isError());
        assertTrue(state().hasMore());
    }

    @Test public void aFailedFirstPageShowsTheErrorState() {
        repository.listPostsCalls.get(0).onError(new IllegalStateException("offline"));

        assertTrue(state().isError());
        assertTrue(state().getItems().isEmpty());
        assertFalse(state().hasMore());
    }

    @Test public void retryStartsFromTheFirstPageAgain() {
        repository.listPostsCalls.get(0).onError(new IllegalStateException("offline"));

        viewModel.retry();

        assertEquals(2, repository.listPostsCalls.size());
        assertNull(repository.listPostsCursors.get(1));
        assertTrue(state().isLoading());
        assertFalse(state().isError());
    }

    @Test public void refreshKeepsTheRowsUntilTheNewPageArrives() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), null));

        viewModel.refresh();

        assertTrue(state().isRefreshing());
        assertEquals(List.of("post-1"), ids(state().getItems()));

        repository.listPostsCalls.get(1).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-2")), null));

        assertEquals(List.of("post-2"), ids(state().getItems()));
        assertFalse(state().isRefreshing());
    }

    @Test public void aFailedRefreshKeepsTheRowsAndShowsNoError() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.refresh();
        repository.listPostsCalls.get(1).onError(new IllegalStateException("offline"));

        assertEquals(List.of("post-1"), ids(state().getItems()));
        assertFalse(state().isError());
        assertFalse(state().isRefreshing());
        assertTrue(state().hasMore());

        viewModel.loadMore();

        assertEquals("cursor-1", repository.listPostsCursors.get(2));
        repository.listPostsCalls.get(2).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-2")), null));
        assertEquals(List.of("post-1", "post-2"), ids(state().getItems()));
    }

    @Test public void loadMoreAppendsTheNextPage() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.loadMore();

        assertEquals("cursor-1", repository.listPostsCursors.get(1));
        assertTrue(state().isLoadingMore());

        repository.listPostsCalls.get(1).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-2")), null));

        assertEquals(List.of("post-1", "post-2"), ids(state().getItems()));
        assertFalse(state().hasMore());
    }

    @Test public void loadMoreWithoutACursorDoesNothing() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), null));

        viewModel.loadMore();

        assertEquals(1, repository.listPostsCalls.size());
    }

    @Test public void aFailedLoadMoreKeepsTheRowsAndTheCursor() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.loadMore();
        repository.listPostsCalls.get(1).onError(new IllegalStateException("offline"));

        assertEquals(List.of("post-1"), ids(state().getItems()));
        assertTrue(state().hasMore());
        assertFalse(state().isLoadingMore());
    }

    @Test public void aFailedLoadMoreDoesNotAutoLoadAgain() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.loadMore();
        repository.listPostsCalls.get(1).onError(new IllegalStateException("offline"));

        // What the auto-load scroll listener still fires while the footer sits failed:
        // the failed page must not silently redispatch until the user retries.
        viewModel.loadMore();
        viewModel.loadMore();

        assertEquals(2, repository.listPostsCalls.size());
    }

    @Test public void aFailedLoadMoreRaisesTheLoadMoreErrorFlag() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.loadMore();
        repository.listPostsCalls.get(1).onError(new IllegalStateException("offline"));

        assertTrue(state().isLoadMoreError());
        assertEquals(List.of("post-1"), ids(state().getItems()));
        assertTrue(state().hasMore());
        assertFalse(state().isLoadingMore());
        assertFalse(state().isError());
    }

    @Test public void retryLoadMoreClearsTheFlagAndReloadsTheSameCursor() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.loadMore();
        repository.listPostsCalls.get(1).onError(new IllegalStateException("offline"));

        viewModel.retryLoadMore();

        assertEquals(3, repository.listPostsCalls.size());
        assertEquals("cursor-1", repository.listPostsCursors.get(2));
        assertTrue(state().isLoadingMore());
        assertFalse(state().isLoadMoreError());

        repository.listPostsCalls.get(2).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-2")), null));

        assertEquals(List.of("post-1", "post-2"), ids(state().getItems()));
        assertFalse(state().isLoadMoreError());
        assertFalse(state().hasMore());
    }

    @Test public void deleteEmitsSuccessOnlyWhenServerCompletes() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        viewModel.deletePost("post-1");

        assertEquals(List.of("post-2"), ids(state().getItems()));
        assertEquals(1, repository.deletePostCalls.size());
        assertNull(viewModel.getOutcome().getValue());

        repository.deletePostCalls.get(0).onComplete();
        assertEquals(FeedViewModel.Outcome.POST_DELETED, viewModel.getOutcome().getValue());
    }

    @Test public void aRefusedDeleteReloadsTheListAndReportsFailure() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        viewModel.deletePost("post-1");
        repository.deletePostCalls.get(0).onError(new IllegalStateException("forbidden"));

        assertEquals(FeedViewModel.Outcome.POST_DELETE_FAILED, viewModel.getOutcome().getValue());
        assertEquals(2, repository.listPostsCalls.size());
    }

    @Test public void searchAndAuthorFilterForwardParamsAndPersistAcrossLoadMore() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));

        viewModel.setAuthorFilter("author-2", "Binh");
        assertEquals("author-2", repository.listPostsAuthorIds.get(1));
        assertNull(repository.listPostsQueries.get(1));
        assertTrue(state().hasActiveFilter());

        viewModel.setSearchQuery("  xin chào  ");
        assertEquals("author-2", repository.listPostsAuthorIds.get(2));
        assertEquals("xin chào", repository.listPostsQueries.get(2));

        repository.listPostsCalls.get(2).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-2")), "cursor-2"));
        viewModel.loadMore();

        assertEquals("cursor-2", repository.listPostsCursors.get(3));
        assertEquals("author-2", repository.listPostsAuthorIds.get(3));
        assertEquals("xin chào", repository.listPostsQueries.get(3));
    }

    @Test public void syncBusUpdatesAndDeletesPostsInPlace() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        FeedRepository.Post edited = new FeedRepository.Post(
                "post-1",
                new FeedRepository.Author("author-1", "An", null),
                "edited body",
                null,
                null,
                "2026-02-25T05:33:20Z",
                "2026-02-25T05:35:00Z",
                4,
                List.of(new FeedRepository.Reaction("\u2764\uFE0F", 1, true)),
                true);
        syncBus.publish(new FeedSyncBus.PostUpdated(edited));

        assertEquals("edited body", state().getItems().get(0).body());
        assertEquals("2026-02-25T05:35:00Z", state().getItems().get(0).editedAt());
        assertEquals(4, state().getItems().get(0).commentCount());

        syncBus.publish(new FeedSyncBus.PostDeleted("post-2"));
        assertEquals(List.of("post-1"), ids(state().getItems()));
    }

    @Test public void aReactionFlipsBeforeTheServerAnswers() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1")), null));

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)),
                state().getItems().get(0).reactions());
        assertEquals("\uD83D\uDC4D", repository.setReactionEmoji.get(0));
        assertTrue(repository.setReactionActive.get(0));
    }

    @Test public void theServerTallyReplacesTheOptimisticOne() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1")), null));
        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        repository.setReactionCalls.get(0).onSuccess(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 9, true)));

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 9, true)),
                state().getItems().get(0).reactions());
    }

    @Test public void aFailedReactionRestoresThePreviousTally() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1")), null));
        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        repository.setReactionCalls.get(0).onError(new IllegalStateException("offline"));

        assertTrue(state().getItems().get(0).reactions().isEmpty());
    }

    @Test public void aSecondTapOnTheSamePostWhileOneIsInFlightIsIgnored() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1")), null));

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");
        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        assertEquals(1, repository.setReactionCalls.size());
        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)),
                state().getItems().get(0).reactions());

        repository.setReactionCalls.get(0).onSuccess(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)));
        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        assertEquals(2, repository.setReactionCalls.size());
        assertFalse(repository.setReactionActive.get(1));
        assertTrue(state().getItems().get(0).reactions().isEmpty());
    }

    @Test public void aReactionOnAnotherPostIsNotBlockedByAnInFlightOne() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");
        viewModel.toggleReaction("post-2", "\uD83D\uDC4D");

        assertEquals(2, repository.setReactionCalls.size());
    }

    @Test public void aFailedReactionAllowsTheNextTap() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1")), null));
        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");
        repository.setReactionCalls.get(0).onError(new IllegalStateException("offline"));

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        assertEquals(2, repository.setReactionCalls.size());
        assertTrue(repository.setReactionActive.get(1));
    }

    @Test public void togglingAnOwnReactionRemovesIt() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(postWithReaction("post-1", "\uD83D\uDC4D", 3, true)), null));

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 2, false)),
                state().getItems().get(0).reactions());
        assertFalse(repository.setReactionActive.get(0));
    }

    @Test public void togglingAnUnknownPostDoesNothing() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), null));

        viewModel.toggleReaction("post-9", "\uD83D\uDC4D");

        assertTrue(repository.setReactionCalls.isEmpty());
    }

    @Test public void postCreatedSyncEventPrependsWithoutDuplicateWhenUnfiltered() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), null));

        FeedRepository.Post created = post("post-new");
        syncBus.publish(new FeedSyncBus.PostCreated(created));
        syncBus.publish(new FeedSyncBus.PostCreated(created));

        assertEquals(List.of("post-new", "post-1"), ids(state().getItems()));
    }

    @Test public void forceRefreshCancelsInFlightLoadMoreAndReloadsPageOne() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), "cursor-1"));
        viewModel.loadMore();
        assertTrue(state().isLoadingMore());

        viewModel.forceRefresh();
        assertTrue(state().isRefreshing());
        assertFalse(state().isLoadingMore());
        assertEquals(3, repository.listPostsCalls.size());
        assertNull(repository.listPostsCursors.get(2));

        repository.listPostsCalls.get(2).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-new"), post("post-1")), null));
        assertEquals(List.of("post-new", "post-1"), ids(state().getItems()));
        assertFalse(state().isRefreshing());
    }

    @Test public void deletePostFailureWhileLoadMoreIsBusyRestoresRowAndReloads() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1"), post("post-2")), "cursor-1"));

        viewModel.deletePost("post-1");
        viewModel.loadMore();
        assertTrue(state().isBusy());

        repository.deletePostCalls.get(0).onError(new IllegalStateException("offline"));

        assertEquals(FeedViewModel.Outcome.POST_DELETE_FAILED, viewModel.getOutcome().getValue());
        assertEquals(List.of("post-1", "post-2"), ids(state().getItems()));
        assertEquals(3, repository.listPostsCalls.size());
        assertNull(repository.listPostsCursors.get(2));
    }

    @Test public void setAuthorFilterUpdatesNameWhenSameAuthorIdMatches() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1")), null));

        viewModel.setAuthorFilter("author-2", null);
        assertNull(state().getAuthorName());

        viewModel.setAuthorFilter("author-2", "Binh");
        assertEquals("Binh", state().getAuthorName());
        assertEquals(2, repository.listPostsCalls.size());
    }

    @Test public void toggleReactionAndSyncUpdatePreserveLoadingMoreFlag() {
        repository.listPostsCalls.get(0).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-1"), post("post-2")), "cursor-1"));
        viewModel.loadMore();
        assertTrue(state().isLoadingMore());

        viewModel.toggleReaction("post-1", "\uD83D\uDC4D");
        assertTrue(state().isLoadingMore());
        assertTrue(state().isBusy());

        repository.setReactionCalls.get(0).onSuccess(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 1, true)));
        assertTrue(state().isLoadingMore());

        FeedRepository.Post updated = new FeedRepository.Post(
                "post-2",
                new FeedRepository.Author("author-1", "An", null),
                "updated body",
                null,
                null,
                "2026-02-25T05:33:20Z",
                "2026-02-25T05:40:00Z",
                1,
                List.of(),
                true);
        syncBus.publish(new FeedSyncBus.PostUpdated(updated));
        assertTrue(state().isLoadingMore());
        assertEquals("updated body", state().getItems().get(1).body());

        repository.listPostsCalls.get(1).onSuccess(
                new FeedRepository.PostPage(List.of(post("post-3")), null));
        assertFalse(state().isLoadingMore());
        assertEquals(List.of("post-1", "post-2", "post-3"), ids(state().getItems()));
    }

    private FeedViewModel.UiState state() {
        return viewModel.getUiState().getValue();
    }

    private static List<String> ids(List<FeedRepository.Post> posts) {
        return posts.stream().map(FeedRepository.Post::id).toList();
    }

    private static FeedRepository.Post post(String id) {
        return new FeedRepository.Post(
                id,
                new FeedRepository.Author("author-1", "An", null),
                "body",
                null,
                "2026-02-25T05:33:20Z",
                0,
                List.of(),
                false);
    }

    private static FeedRepository.Post postWithReaction(
            String id, String emoji, int count, boolean mine) {
        return new FeedRepository.Post(
                id,
                new FeedRepository.Author("author-1", "An", null),
                "body",
                null,
                "2026-02-25T05:33:20Z",
                0,
                List.of(new FeedRepository.Reaction(emoji, count, mine)),
                false);
    }
}
