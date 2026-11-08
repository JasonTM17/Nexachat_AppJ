package com.nexachat.feature.inbox;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subjects.SingleSubject;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Collections;
import java.util.List;
import com.nexachat.core.common.Unit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class InboxViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private ConversationRepository repository;
    private BehaviorProcessor<List<ConversationSummary>> inboxFlow;
    private SavedStateHandle savedStateHandle;
    private InboxViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(ConversationRepository.class);
        inboxFlow = BehaviorProcessor.createDefault(Collections.emptyList());
        when(repository.inboxFlow()).thenReturn(inboxFlow);
        when(repository.refreshInboxPage()).thenReturn(Single.just(
                new Result.Success<Boolean>(Boolean.TRUE)));
        when(repository.loadNextInboxPage()).thenReturn(Single.just(
                new Result.Success<Boolean>(Boolean.FALSE)));
        savedStateHandle = new SavedStateHandle();
        viewModel = new InboxViewModel(repository, savedStateHandle);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void roomStreamMapsConversationsAndClearsLoading() {
        inboxFlow.onNext(List.of(conversation("c-1", 2)));

        InboxUiState state = viewModel.getUiState().getValue();
        assertEquals(1, state.getConversations().size());
        assertEquals("c-1", state.getConversations().get(0).getId());
        assertFalse(state.isLoading());
    }

    @Test
    public void unreadCountsMapIntoRowsAndSumToTotalBadge() {
        inboxFlow.onNext(List.of(
                conversation("c-1", 2),
                conversation("c-2", 3),
                conversation("c-3", 0)));

        InboxUiState state = viewModel.getUiState().getValue();
        assertEquals(List.of(2, 3, 0), List.of(
                state.getConversations().get(0).getUnreadCount(),
                state.getConversations().get(1).getUnreadCount(),
                state.getConversations().get(2).getUnreadCount()));
        assertEquals(5, state.getTotalUnread());
    }

    @Test
    public void displayNamePrefersGroupTitleThenPeerName() {
        inboxFlow.onNext(List.of(
                conversation("g-1", 0, "Team", "Team Chat"),
                conversation("c-1", 0, "Peer c-1", null)));

        InboxUiState state = viewModel.getUiState().getValue();
        assertEquals("Team Chat", state.getConversations().get(0).getDisplayName());
        assertEquals("Peer c-1", state.getConversations().get(1).getDisplayName());
    }

    @Test
    public void refreshFailureSurfacesErrorButKeepsList() {
        inboxFlow.onNext(List.of(conversation("c-1", 1)));
        when(repository.refreshInboxPage()).thenReturn(Single.just(
                Result.failure(new ApiError.Network("down"))));

        viewModel.refresh();

        InboxUiState state = viewModel.getUiState().getValue();
        assertEquals(new ApiError.Network("down"), state.getError());
        assertEquals(1, state.getConversations().size());
    }

    @Test
    public void successfulRefreshClearsPreviousError() {
        when(repository.refreshInboxPage()).thenReturn(Single.just(
                Result.failure(ApiError.RateLimited.INSTANCE)));
        viewModel.refresh();
        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());

        when(repository.refreshInboxPage()).thenReturn(Single.just(
                new Result.Success<Boolean>(Boolean.TRUE)));
        viewModel.refresh();

        assertNull(viewModel.getUiState().getValue().getError());
    }

    @Test
    public void concurrentNearEndTriggersShareOnePageRequest() {
        SingleSubject<Result<Boolean>> pending = SingleSubject.create();
        when(repository.loadNextInboxPage()).thenReturn(pending);

        viewModel.loadNextPage();
        viewModel.loadNextPage();

        verify(repository, times(1)).loadNextInboxPage();
        assertTrue(viewModel.getUiState().getValue().isLoadingMore());
        pending.onSuccess(new Result.Success<Boolean>(Boolean.FALSE));
        assertFalse(viewModel.getUiState().getValue().isLoadingMore());
        assertFalse(viewModel.getUiState().getValue().hasMore());
    }

    @Test
    public void failedNextPageKeepsCursorAvailableForRetry() {
        ApiError failure = new ApiError.Network("down");
        when(repository.loadNextInboxPage()).thenReturn(
                Single.just(Result.failure(failure)),
                Single.just(new Result.Success<Boolean>(Boolean.FALSE)));

        viewModel.loadNextPage();

        assertEquals(failure, viewModel.getUiState().getValue().getNextPageError());
        assertTrue(viewModel.getUiState().getValue().hasMore());
        viewModel.loadNextPage();

        assertNull(viewModel.getUiState().getValue().getNextPageError());
        assertFalse(viewModel.getUiState().getValue().hasMore());
        verify(repository, times(2)).loadNextInboxPage();
    }

    @Test
    public void exhaustedInboxDoesNotRequestAnotherPage() {
        when(repository.refreshInboxPage()).thenReturn(Single.just(
                new Result.Success<Boolean>(Boolean.FALSE)));

        viewModel.refresh();
        viewModel.loadNextPage();

        assertFalse(viewModel.getUiState().getValue().hasMore());
        verify(repository, never()).loadNextInboxPage();
    }

    @Test
    public void selectedFilterIsPersistedAndRestoredWithVisibleRows() {
        inboxFlow.onNext(List.of(
                conversation("direct-unread", 2),
                conversation("direct-read", 0),
                conversation("group-unread", 1, "Team", "Team Chat")));

        viewModel.selectFilter(InboxFilter.UNREAD);

        InboxUiState selected = viewModel.getUiState().getValue();
        assertEquals(InboxFilter.UNREAD, selected.getSelectedFilter());
        assertEquals(List.of("direct-unread", "group-unread"),
                selected.getVisibleConversations().stream()
                        .map(ConversationSummary::getId).toList());

        InboxViewModel restored = new InboxViewModel(repository, savedStateHandle);

        assertEquals(InboxFilter.UNREAD,
                restored.getUiState().getValue().getSelectedFilter());
        assertEquals(List.of("direct-unread", "group-unread"),
                restored.getUiState().getValue().getVisibleConversations().stream()
                        .map(ConversationSummary::getId).toList());
    }

    private static ConversationSummary conversation(String id, int unread) {
        return conversation(id, unread, "Peer " + id, null);
    }

    private static ConversationSummary conversation(
            String id, int unread, String name, String title) {
        return new ConversationSummary(
                id,
                title == null ? "DIRECT" : "GROUP",
                title,
                "last " + id,
                name,
                1_700_000_000_000L,
                42L,
                unread,
                name,
                null,
                null,
                0L,
                null,
                null,
                false,
                null);
    }
}
