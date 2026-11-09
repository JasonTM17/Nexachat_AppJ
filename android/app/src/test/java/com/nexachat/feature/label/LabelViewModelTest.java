package com.nexachat.feature.label;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.network.SessionEvents;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class LabelViewModelTest {
    private static final String FILTER_KEY = "inbox_selected_label_id";

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeRepository repository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeRepository();
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void labelLimitStopsCreateBeforeRepositoryCall() {
        for (int index = 0; index < LabelRules.MAX_LABELS; index++) {
            repository.labels.add(label("label-" + index, "Label " + index));
        }
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();

        viewModel.createLabel("Extra", LabelColor.BLUE);

        assertEquals(LabelUiState.Error.LABEL_LIMIT, state(viewModel).getError());
        assertEquals(0, repository.createCalls);
        assertEquals(LabelRules.MAX_LABELS, state(viewModel).getLabels().size());
    }

    @Test
    public void assignmentLimitStopsFourthLabelBeforeRepositoryCall() {
        repository.labels.addAll(List.of(
                label("one", "One"), label("two", "Two"),
                label("three", "Three"), label("four", "Four")));
        repository.cached.put("conversation-1", List.of("one", "two", "three"));
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "four", true);

        assertEquals(LabelUiState.Error.ASSIGNMENT_LIMIT, state(viewModel).getError());
        assertEquals(List.of("one", "two", "three"), state(viewModel).getAssignmentIds());
        assertEquals(0, repository.assignCalls);
    }

    @Test
    public void rateLimitedAssignmentRollsBackOptimisticRoomCacheAndUi() {
        repository.labels.addAll(List.of(label("one", "One"), label("two", "Two")));
        repository.cached.put("conversation-1", List.of("one"));
        repository.assignmentFailure = new LabelApiException(429, "RATE_LIMITED");
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "two", true);

        assertEquals(List.of("one"), state(viewModel).getAssignmentIds());
        assertEquals(List.of("one"), repository.cached.get("conversation-1"));
        assertEquals(LabelUiState.Error.RATE_LIMITED, state(viewModel).getError());
        assertEquals(List.of(List.of("one", "two"), List.of("one")),
                repository.cacheWrites);
    }

    @Test
    public void deleteWaitsUntilAnAssignmentWriteFinishes() {
        repository.labels.add(label("work", "Work"));
        repository.assignmentGate = CompletableSubject.create();
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "work", true);
        viewModel.deleteLabel("work");

        assertTrue(state(viewModel).isAssignmentSaving());
        assertEquals(0, repository.deleteCalls);
        assertEquals(1, state(viewModel).getLabels().size());

        repository.assignmentGate.onComplete();
        viewModel.deleteLabel("work");

        assertEquals(1, repository.deleteCalls);
        assertTrue(state(viewModel).getLabels().isEmpty());
    }

    @Test
    public void selectedServerFilterRestoresFromSavedStateHandle() {
        repository.labels.add(label("work", "Work"));
        repository.filteredIds = Set.of("conversation-1");
        SavedStateHandle savedState = new SavedStateHandle();
        LabelViewModel first = new LabelViewModel(repository, savedState);
        first.load();
        first.selectFilter("work");

        LabelViewModel restored = new LabelViewModel(repository, savedState);
        restored.load();

        assertEquals("work", state(restored).getSelectedFilterId());
        assertEquals(Set.of("conversation-1"), state(restored).getFilteredConversationIds());
        assertTrue(repository.filterRequests.contains("work"));
        assertFalse(state(restored).isFilterLoading());
    }

    @Test
    public void logoutClearsAccountPrivateLabelsFilterAndAssignmentsBeforeNextLoad() {
        repository.labels.add(label("account-a", "Account A"));
        repository.cached.put("conversation-a", List.of("account-a"));
        repository.filteredIds = Set.of("conversation-a");
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "account-a"));
        SessionEvents sessionEvents = new SessionEvents();
        LabelViewModel viewModel = new LabelViewModel(repository, savedState, sessionEvents);
        viewModel.load();
        viewModel.loadAssignment("conversation-a");
        assertEquals("account-a", state(viewModel).getSelectedFilterId());
        assertEquals(List.of("account-a"), state(viewModel).getAssignmentIds());

        sessionEvents.emitLoggedOut();

        assertTrue(state(viewModel).getLabels().isEmpty());
        assertFalse(state(viewModel).isLabelsLoaded());
        assertNull(state(viewModel).getSelectedFilterId());
        assertNull(savedState.get(FILTER_KEY));
        assertTrue(state(viewModel).getFilteredConversationIds().isEmpty());
        assertNull(state(viewModel).getAssignmentConversationId());
        assertTrue(state(viewModel).getAssignmentIds().isEmpty());
        assertEquals(LabelUiState.Error.NONE, state(viewModel).getFilterError());

        repository.labels.clear();
        repository.labels.add(label("account-b", "Account B"));
        repository.filteredIds = Set.of("conversation-b");
        viewModel.load();

        assertEquals(List.of("account-b"), ids(state(viewModel).getLabels()));
        assertNull(state(viewModel).getSelectedFilterId());
        assertTrue(state(viewModel).getFilteredConversationIds().isEmpty());
    }

    @Test
    public void replayedLogoutIsAppliedBeforeInjectedViewModelLoadsCurrentAccount() {
        SessionEvents sessionEvents = new SessionEvents();
        sessionEvents.emitLoggedOut();
        repository.labels.add(label("account-b", "Account B"));
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "account-a"));

        LabelViewModel viewModel = new LabelViewModel(
                repository, savedState, sessionEvents, Schedulers.trampoline());
        viewModel.load();

        assertEquals(List.of("account-b"), ids(state(viewModel).getLabels()));
        assertNull(state(viewModel).getSelectedFilterId());
        assertNull(savedState.get(FILTER_KEY));
        assertTrue(state(viewModel).isLabelsLoaded());
    }

    @Test
    public void backgroundLogoutResetWaitsForMainThreadScheduler() throws Exception {
        repository.labels.add(label("account-a", "Account A"));
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "account-a"));
        SessionEvents sessionEvents = new SessionEvents();
        TestScheduler mainThreadScheduler = new TestScheduler();
        LabelViewModel viewModel = new LabelViewModel(
                repository, savedState, sessionEvents, mainThreadScheduler);
        viewModel.load();
        assertEquals("account-a", state(viewModel).getSelectedFilterId());

        Thread logoutThread = new Thread(sessionEvents::emitLoggedOut);
        logoutThread.start();
        logoutThread.join();

        assertEquals("account-a", savedState.get(FILTER_KEY));
        assertEquals("account-a", state(viewModel).getSelectedFilterId());

        mainThreadScheduler.triggerActions();

        assertNull(savedState.get(FILTER_KEY));
        assertNull(state(viewModel).getSelectedFilterId());
        assertTrue(state(viewModel).getLabels().isEmpty());
    }

    @Test
    public void labelListResponseClearsStaleSavedFilterOnMainThreadScheduler() throws Exception {
        SingleSubject<List<ConversationLabel>> labelsResponse = SingleSubject.create();
        repository.labelsResponse = labelsResponse;
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "removed-label"));
        TestScheduler mainThreadScheduler = new TestScheduler();
        LabelViewModel viewModel = new LabelViewModel(
                repository, savedState, new SessionEvents(), mainThreadScheduler);
        viewModel.load();

        Thread responseThread = new Thread(() -> labelsResponse.onSuccess(List.of()));
        responseThread.start();
        responseThread.join();

        assertEquals("removed-label", savedState.get(FILTER_KEY));
        mainThreadScheduler.triggerActions();

        assertNull(savedState.get(FILTER_KEY));
        assertNull(state(viewModel).getSelectedFilterId());
        assertTrue(state(viewModel).isLabelsLoaded());
    }

    @Test
    public void deleteFailureRestoresSavedFilterOnMainThreadScheduler() throws Exception {
        repository.labels.add(label("work", "Work"));
        repository.filteredIds = Set.of("conversation-1");
        CompletableSubject deleteResponse = CompletableSubject.create();
        repository.deleteResponse = deleteResponse;
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "work"));
        TestScheduler mainThreadScheduler = new TestScheduler();
        LabelViewModel viewModel = new LabelViewModel(
                repository, savedState, new SessionEvents(), mainThreadScheduler);
        viewModel.load();
        viewModel.deleteLabel("work");
        assertNull(savedState.get(FILTER_KEY));

        Thread responseThread = new Thread(() -> deleteResponse.onError(new IOException("offline")));
        responseThread.start();
        responseThread.join();

        assertNull(savedState.get(FILTER_KEY));
        mainThreadScheduler.triggerActions();

        assertEquals("work", savedState.get(FILTER_KEY));
        assertEquals("work", state(viewModel).getSelectedFilterId());
        assertEquals(LabelUiState.Error.OFFLINE, state(viewModel).getError());
    }

    @Test
    public void staleAssignmentReadCannotOverwritePendingWriteOrReopenedDialog() {
        repository.labels.add(label("work", "Work"));
        repository.cached.put("conversation-1", List.of());
        repository.assignmentGate = CompletableSubject.create();
        SingleSubject<List<String>> staleCacheRead = SingleSubject.create();
        repository.assignmentResponses.add(staleCacheRead);
        repository.assignmentResponses.add(Single.just(List.of()));
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.loadAssignment("conversation-1");
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "work", true);
        staleCacheRead.onSuccess(List.of());
        viewModel.loadAssignment("conversation-1");

        assertEquals(List.of("work"), state(viewModel).getAssignmentIds());
        assertTrue(state(viewModel).isAssignmentSaving());
        assertFalse(state(viewModel).isAssignmentLoading());

        repository.assignmentGate.onComplete();

        assertEquals(List.of("work"), state(viewModel).getAssignmentIds());
        assertFalse(state(viewModel).isAssignmentSaving());
    }

    @Test
    public void deletingLabelClearsSelectedFilterAndAssignmentAndRemovesCachedId() {
        repository.labels.add(label("work", "Work"));
        repository.labels.add(label("other", "Other"));
        repository.cached.put("conversation-1", List.of("work", "other"));
        repository.filteredIds = Set.of("conversation-1");
        SavedStateHandle savedState = new SavedStateHandle(Map.of(FILTER_KEY, "work"));
        LabelViewModel viewModel = new LabelViewModel(repository, savedState);
        viewModel.load();
        viewModel.loadAssignment("conversation-1");

        viewModel.deleteLabel("work");

        assertEquals(List.of("other"), ids(state(viewModel).getLabels()));
        assertNull(state(viewModel).getSelectedFilterId());
        assertNull(savedState.get(FILTER_KEY));
        assertEquals(List.of("other"), state(viewModel).getAssignmentIds());
        assertEquals(List.of("other"), repository.cached.get("conversation-1"));
        assertEquals(1, repository.cacheRemovalCalls);
    }

    @Test
    public void createFailureRemovesOptimisticLabelAndMapsContractStatus() {
        int[] statuses = {400, 409, 429};
        LabelUiState.Error[] expected = {
            LabelUiState.Error.VALIDATION,
            LabelUiState.Error.CONFLICT,
            LabelUiState.Error.RATE_LIMITED
        };
        for (int index = 0; index < statuses.length; index++) {
            FakeRepository fake = new FakeRepository();
            fake.createFailure = new LabelApiException(statuses[index], "CONTRACT_ERROR");
            LabelViewModel viewModel = new LabelViewModel(fake);
            viewModel.load();

            viewModel.createLabel("New label", LabelColor.INDIGO);

            assertTrue(state(viewModel).getLabels().isEmpty());
            assertFalse(state(viewModel).isLabelsMutating());
            assertEquals(expected[index], state(viewModel).getError());
        }
    }

    @Test
    public void filterNetworkFailureShowsOfflineState() {
        repository.labels.add(label("work", "Work"));
        repository.filterFailure = new IOException("offline");
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();

        viewModel.selectFilter("work");

        assertEquals(LabelUiState.Error.OFFLINE, state(viewModel).getFilterError());
        assertEquals(LabelUiState.Error.NONE, state(viewModel).getError());
        assertFalse(state(viewModel).isFilterLoading());
    }

    @Test
    public void assignmentFailureDoesNotReplaceSelectedFilterMembershipOrError() {
        repository.labels.add(label("work", "Work"));
        repository.cached.put("conversation-1", List.of("work"));
        repository.filteredIds = Set.of("conversation-1");
        repository.unassignFailure = new LabelApiException(429, "RATE_LIMITED");
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.selectFilter("work");
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "work", false);

        assertEquals(Set.of("conversation-1"), state(viewModel).getFilteredConversationIds());
        assertEquals(LabelUiState.Error.NONE, state(viewModel).getFilterError());
        assertEquals(LabelUiState.Error.RATE_LIMITED, state(viewModel).getError());
    }

    @Test
    public void assignmentOptimisticallyUpdatesSettledSelectedFilterMembership() {
        repository.labels.add(label("work", "Work"));
        repository.cached.put("conversation-1", List.of());
        repository.filteredIds = Set.of();
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.selectFilter("work");
        viewModel.loadAssignment("conversation-1");

        viewModel.toggleAssignment("conversation-1", "work", true);

        assertEquals(Set.of("conversation-1"), state(viewModel).getFilteredConversationIds());
        assertEquals(LabelUiState.Error.NONE, state(viewModel).getFilterError());
    }

    @Test
    public void filterResponseStartedBeforeAssignmentCannotOverwriteRefreshedMembership() {
        repository.labels.add(label("work", "Work"));
        repository.cached.put("conversation-1", List.of());
        SingleSubject<Set<String>> staleFilterResponse = SingleSubject.create();
        repository.filterResponses.add(staleFilterResponse);
        repository.filterResponses.add(Single.just(Set.of("conversation-1")));
        LabelViewModel viewModel = new LabelViewModel(repository);
        viewModel.load();
        viewModel.selectFilter("work");
        viewModel.loadAssignment("conversation-1");
        assertTrue(state(viewModel).isFilterLoading());

        viewModel.toggleAssignment("conversation-1", "work", true);
        staleFilterResponse.onSuccess(Set.of());

        assertEquals(Set.of("conversation-1"), state(viewModel).getFilteredConversationIds());
        assertFalse(state(viewModel).isFilterLoading());
    }

    private static ConversationLabel label(String id, String name) {
        return new ConversationLabel(id, name, LabelColor.INDIGO, false);
    }

    private static LabelUiState state(LabelViewModel viewModel) {
        return viewModel.getUiState().getValue();
    }

    private static List<String> ids(List<ConversationLabel> labels) {
        return labels.stream().map(ConversationLabel::getId).toList();
    }

    private static final class FakeRepository implements LabelRepository {
        private final List<ConversationLabel> labels = new ArrayList<>();
        private final Map<String, List<String>> cached = new HashMap<>();
        private final List<List<String>> cacheWrites = new ArrayList<>();
        private final List<Single<List<String>>> assignmentResponses = new ArrayList<>();
        private final List<String> filterRequests = new ArrayList<>();
        private final List<Single<Set<String>>> filterResponses = new ArrayList<>();
        private Set<String> filteredIds = Set.of();
        private Single<List<ConversationLabel>> labelsResponse;
        private Completable deleteResponse;
        private Throwable createFailure;
        private Throwable assignmentFailure;
        private Throwable unassignFailure;
        private Throwable filterFailure;
        private CompletableSubject assignmentGate;
        private int createCalls;
        private int assignCalls;
        private int deleteCalls;
        private int cacheRemovalCalls;

        @Override public Single<List<ConversationLabel>> listLabels() {
            if (labelsResponse != null) return labelsResponse;
            return Single.fromCallable(() -> List.copyOf(labels));
        }

        @Override public Single<ConversationLabel> createLabel(String name, LabelColor color) {
            createCalls++;
            if (createFailure != null) return Single.error(createFailure);
            ConversationLabel created = new ConversationLabel(
                    "created-" + createCalls, name, color, false);
            labels.add(created);
            return Single.just(created);
        }

        @Override public Single<ConversationLabel> updateLabel(
                String labelId, String name, LabelColor color) {
            ConversationLabel updated = new ConversationLabel(labelId, name, color, false);
            labels.removeIf(label -> labelId.equals(label.getId()));
            labels.add(updated);
            return Single.just(updated);
        }

        @Override public Completable deleteLabel(String labelId) {
            deleteCalls++;
            if (deleteResponse != null) return deleteResponse;
            return Completable.fromAction(() -> labels.removeIf(label -> labelId.equals(label.getId())));
        }

        @Override public Completable assignLabel(String conversationId, String labelId) {
            assignCalls++;
            if (assignmentFailure != null) return Completable.error(assignmentFailure);
            Completable request = assignmentGate == null
                    ? Completable.complete() : assignmentGate;
            return request.andThen(Completable.fromAction(() -> {
                List<String> next = new ArrayList<>(cached.getOrDefault(conversationId, List.of()));
                if (!next.contains(labelId)) next.add(labelId);
                cached.put(conversationId, List.copyOf(next));
            }));
        }

        @Override public Completable unassignLabel(String conversationId, String labelId) {
            if (unassignFailure != null) return Completable.error(unassignFailure);
            return Completable.fromAction(() -> {
                List<String> next = new ArrayList<>(cached.getOrDefault(conversationId, List.of()));
                next.remove(labelId);
                cached.put(conversationId, List.copyOf(next));
            });
        }

        @Override public Single<Set<String>> filterConversationIds(String labelId) {
            filterRequests.add(labelId);
            if (!filterResponses.isEmpty()) return filterResponses.remove(0);
            if (filterFailure != null) return Single.error(filterFailure);
            return Single.just(filteredIds);
        }

        @Override public Single<List<String>> cachedLabelIds(String conversationId) {
            if (!assignmentResponses.isEmpty()) return assignmentResponses.remove(0);
            return Single.fromCallable(() -> cached.getOrDefault(conversationId, List.of()));
        }

        @Override public Completable cacheLabelIds(String conversationId, List<String> labelIds) {
            return Completable.fromAction(() -> {
                List<String> copy = List.copyOf(labelIds);
                cached.put(conversationId, copy);
                cacheWrites.add(copy);
            });
        }

        @Override public Completable removeLabelFromCache(String labelId) {
            cacheRemovalCalls++;
            return Completable.fromAction(() -> cached.replaceAll((conversationId, ids) ->
                    ids.stream().filter(id -> !labelId.equals(id)).toList()));
        }
    }
}
