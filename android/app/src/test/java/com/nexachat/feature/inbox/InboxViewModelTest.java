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
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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
        when(repository.refreshInboxPage(false)).thenReturn(Single.just(
                new Result.Success<Boolean>(Boolean.TRUE)));
        when(repository.refreshInboxPage(true)).thenReturn(Single.just(
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
        when(repository.refreshInboxPage(false)).thenReturn(Single.just(
                Result.failure(new ApiError.Network("down"))));

        viewModel.refresh();

        InboxUiState state = viewModel.getUiState().getValue();
        assertEquals(new ApiError.Network("down"), state.getError());
        assertEquals(1, state.getConversations().size());
    }

    @Test
    public void successfulRefreshClearsPreviousError() {
        when(repository.refreshInboxPage(false)).thenReturn(Single.just(
                Result.failure(ApiError.RateLimited.INSTANCE)));
        viewModel.refresh();
        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());

        when(repository.refreshInboxPage(false)).thenReturn(Single.just(
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
        when(repository.refreshInboxPage(false)).thenReturn(Single.just(
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

    @Test
    public void togglePinCallsPinWhenUnpinned() {
        ConversationSummary unpinned = conversation("c-1", 0);
        when(repository.setConversationPinned("c-1", true))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.togglePin(unpinned);

        verify(repository, times(1)).setConversationPinned("c-1", true);
        assertNull(viewModel.getPinError().getValue());
    }

    @Test
    public void setConversationAiOptOutDelegatesToRepository() {
        when(repository.setAiOptOut("c-1", true))
                .thenReturn(Single.just(new Result.Success<>(Boolean.TRUE)));

        viewModel.setConversationAiOptOut("c-1", true);

        verify(repository).setAiOptOut("c-1", true);
    }

    @Test
    public void togglePinCallsUnpinWhenPinned() {
        ConversationSummary pinned = pinnedConversation("c-1");
        when(repository.setConversationPinned("c-1", false))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.togglePin(pinned);

        verify(repository, times(1)).setConversationPinned("c-1", false);
        assertNull(viewModel.getPinError().getValue());
    }

    @Test
    public void togglePinSurfacesCapErrorAndAllowsClearing() {
        ConversationSummary unpinned = conversation("c-1", 0);
        ApiError capError = new ApiError.Unknown("CONVERSATION_PIN_LIMIT");
        when(repository.setConversationPinned("c-1", true))
                .thenReturn(Single.just(Result.failure(capError)));

        viewModel.togglePin(unpinned);

        assertEquals(capError, viewModel.getPinError().getValue());

        viewModel.clearPinError();
        assertNull(viewModel.getPinError().getValue());
    }

    @Test
    public void archivedFilterPreservesPinnedOrderingInsideArchivedVariant() {
        ConversationSummary unpinnedArchived = new ConversationSummary(
                "c-arch-1", "DIRECT", null, "last 1", "Peer 1", 1_700_000_000_100L,
                42L, 0, "Peer 1", null, null, 0L, 0L, null, null, false, null,
                false, null, true, false, false, null);
        ConversationSummary pinnedArchived = new ConversationSummary(
                "c-arch-2", "DIRECT", null, "last 2", "Peer 2", 1_700_000_000_050L,
                42L, 0, "Peer 2", null, null, 0L, 0L, null, null, false, null,
                false, null, true, false, true, 1_700_000_000_500L);
        ConversationSummary activeUnpinned = conversation("c-active", 0);

        // In Room/repository order, pinnedArchived precedes unpinnedArchived
        inboxFlow.onNext(List.of(activeUnpinned, pinnedArchived, unpinnedArchived));

        viewModel.selectFilter(InboxFilter.ARCHIVED);

        List<String> visibleIds = viewModel.getUiState().getValue()
                .getVisibleConversations().stream().map(ConversationSummary::getId).toList();
        assertEquals(List.of("c-arch-2", "c-arch-1"), visibleIds);
    }

    private static ConversationSummary pinnedConversation(String id) {
        return new ConversationSummary(
                id, "DIRECT", null, "last " + id, "Peer " + id, 1_700_000_000_000L,
                42L, 0, "Peer " + id, null, null, 0L, 0L, null, null, false, null,
                false, null, false, false, true, 1_700_000_000_000L);
    }

    @Test
    public void filterSwitchedDuringRefreshRunsAfterTheInFlightRequest() {
        SingleSubject<Result<Boolean>> pending = SingleSubject.create();
        when(repository.refreshInboxPage(false)).thenReturn(pending);
        InboxViewModel switching = new InboxViewModel(repository, new SavedStateHandle());

        switching.selectFilter(InboxFilter.ARCHIVED);

        verify(repository, never()).refreshInboxPage(true);
        pending.onSuccess(new Result.Success<>(Boolean.TRUE));
        verify(repository).refreshInboxPage(true);
    }

    @Test
    public void archivedFilterRequestsArchivedPageAndShowsOnlyArchivedRows() {
        inboxFlow.onNext(List.of(
                conversation("active", 1),
                archived("c-arch", 3)));

        viewModel.selectFilter(InboxFilter.ARCHIVED);

        verify(repository).refreshInboxPage(true);
        assertEquals(List.of("c-arch"), viewModel.getUiState().getValue()
                .getVisibleConversations().stream().map(ConversationSummary::getId).toList());
    }

    @Test
    public void leavingArchivedFilterRefreshesTheDefaultInbox() {
        viewModel.selectFilter(InboxFilter.ARCHIVED);
        clearInvocations(repository);

        viewModel.selectFilter(InboxFilter.ALL);

        verify(repository).refreshInboxPage(false);
    }

    @Test
    public void archivedUnreadRowsDoNotCountTowardTheBadge() {
        inboxFlow.onNext(List.of(
                conversation("active", 2),
                archived("c-arch", 5)));

        assertEquals(2, viewModel.getUiState().getValue().getTotalUnread());
    }

    @Test
    public void muteFailureSurfacesThroughTheOneShotEvent() {
        ApiError failure = new ApiError.Network("down");
        when(repository.muteConversation(eq("c-1"), any(Instant.class)))
                .thenReturn(Single.just(Result.failure(failure)));

        viewModel.muteConversation("c-1", Instant.parse("2026-11-09T01:00:00Z"));

        InboxEvent event = viewModel.getEvents().getValue().consume();
        assertTrue(event instanceof InboxErrorEvent);
        assertEquals(failure, ((InboxErrorEvent) event).getError());
    }

    @Test
    public void successfulMuteSurfacesUndoSnackbarEvent() {
        when(repository.muteConversation(eq("c-1"), any(Instant.class)))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.muteConversation("c-1", Instant.parse("2026-11-09T01:00:00Z"));

        InboxSingleLiveEvent.Event<InboxEvent> raw = viewModel.getEvents().getValue();
        assertNotNull(raw);
        InboxEvent event = raw.consume();
        assertTrue(event instanceof InboxPreferenceSuccess);
        assertNotNull(((InboxPreferenceSuccess) event).getUndo());
    }

    @Test
    public void archiveActionDelegatesTheRequestedFlag() {
        when(repository.setConversationArchived("c-1", true))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.setConversationArchived("c-1", true);

        verify(repository).setConversationArchived("c-1", true);
    }

    @Test
    public void archiveSuccessSurfacesUndoSnackbarEvent() {
        when(repository.setConversationArchived(eq("c-1"), anyBoolean()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.setConversationArchived("c-1", true);

        InboxSingleLiveEvent.Event<InboxEvent> raw = viewModel.getEvents().getValue();
        assertNotNull(raw);
        InboxEvent event = raw.consume();
        assertNotNull(event);
        assertTrue(event instanceof InboxPreferenceSuccess);
        InboxPreferenceSuccess success = (InboxPreferenceSuccess) event;
        assertEquals(com.nexachat.R.string.conversation_archived, success.getMessageId());
        assertNotNull(success.getUndo());
        success.getUndo().run();
        verify(repository).setConversationArchived("c-1", false);
    }

    private static ConversationSummary conversation(String id, int unread) {
        return conversation(id, unread, "Peer " + id, null);
    }

    private static ConversationSummary archived(String id, int unread) {
        return new ConversationSummary(
                id,
                "DIRECT",
                null,
                "last " + id,
                "Peer " + id,
                1_700_000_000_000L,
                42L,
                unread,
                "Peer " + id,
                null,
                null,
                0L,
                0L,
                null,
                null,
                false,
                null,
                false,
                null,
                true,
                false);
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
