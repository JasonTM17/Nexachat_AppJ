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
    private FeedViewModel viewModel;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeFeedRepository();
        viewModel = new FeedViewModel(repository);
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

    @Test public void deleteRemovesTheRowAtOnce() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        viewModel.deletePost("post-1");

        assertEquals(List.of("post-2"), ids(state().getItems()));
        assertEquals(1, repository.deletePostCalls.size());
    }

    @Test public void aRefusedDeleteReloadsTheList() {
        repository.listPostsCalls.get(0).onSuccess(new FeedRepository.PostPage(
                List.of(post("post-1"), post("post-2")), null));

        viewModel.deletePost("post-1");
        repository.deletePostCalls.get(0).onError(new IllegalStateException("forbidden"));

        assertEquals(2, repository.listPostsCalls.size());
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
