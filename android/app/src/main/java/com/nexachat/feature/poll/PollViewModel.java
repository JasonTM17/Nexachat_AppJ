package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

/**
 * Owns the group poll list, local vote drafts, optimistic votes and the versioned
 * change feed. Polls have no WebSocket event, so the visible board polls
 * {@code /polls/changes} and merges by poll id and version.
 */
@HiltViewModel
public final class PollViewModel extends ViewModel {

    public static final String ARG_CONVERSATION_ID = "conversationId";
    public static final String ARG_IS_GROUP = "isGroup";
    private static final int MAX_CHANGE_PAGES_PER_REFRESH = 5;

    private static final Comparator<Poll> NEWEST_FIRST =
            Comparator.comparing(Poll::getCreatedAt)
                    .reversed()
                    .thenComparing(Poll::getId, Comparator.reverseOrder());

    private final String conversationId;
    private final boolean group;
    private final PollRepository repository;
    private final PollViewerSource viewerSource;
    private final Clock clock;
    private final MutableLiveData<PollUiState> uiState = new MutableLiveData<>(new PollUiState());
    private final PollEventLiveData events = new PollEventLiveData();
    private final List<Poll> polls = new ArrayList<>();
    private final Map<String, LinkedHashSet<String>> selections = new HashMap<>();
    private final Map<String, String> voterNames = new LinkedHashMap<>();
    private final List<PollRepository.Cancellable> activeRequests = new ArrayList<>();
    @Nullable private PollViewerSource.Cancellable viewerRequest;

    private boolean loading;
    private boolean loaded;
    private boolean loadingMore;
    private boolean hasMore;
    private boolean creating;
    private boolean changesInFlight;
    @Nullable private String nextBefore;
    @Nullable private String votingPollId;
    @Nullable private String createdPollId;
    @Nullable private String myUserId;
    @Nullable private PollRepository.Failure loadError;
    @Nullable private PollRepository.Failure refreshError;
    @Nullable private PollRepository.Failure createError;
    private boolean admin;
    private long lastSince;
    private int generation;

    @Inject
    public PollViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull PollRepository repository,
            @NonNull PollViewerSource viewerSource) {
        this(savedState, repository, viewerSource, Clock.systemUTC());
    }

    @VisibleForTesting
    PollViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull PollRepository repository,
            @NonNull PollViewerSource viewerSource,
            @NonNull Clock clock) {
        Objects.requireNonNull(savedState, "savedState");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.viewerSource = Objects.requireNonNull(viewerSource, "viewerSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        String savedId = savedState.get(ARG_CONVERSATION_ID);
        conversationId = savedId == null ? "" : savedId;
        Boolean savedGroup = savedState.get(ARG_IS_GROUP);
        group = savedGroup == null || savedGroup;
        if (!group) {
            loaded = true;
            loadError = PollRepository.Failure.NOT_A_GROUP;
        }
        loadViewer();
        publish();
    }

    @NonNull
    public LiveData<PollUiState> getUiState() {
        return uiState;
    }

    @NonNull
    public LiveData<PollEventLiveData.Event> getEvents() {
        return events;
    }

    public boolean isGroup() {
        return group;
    }

    public void load() {
        if (!group || loading || loaded) {
            return;
        }
        requestPage(null, true);
    }

    /** On resume: read the change feed instead of reloading the first page. */
    public void refresh() {
        if (!group || !loaded || loading || loadError != null) {
            return;
        }
        refreshChanges();
    }

    public void retry() {
        if (!group || loading) {
            return;
        }
        loadError = null;
        loaded = false;
        requestPage(null, true);
    }

    /** Retries a failed change-feed refresh while keeping the cached poll list visible. */
    public void retryRefresh() {
        if (!group || !loaded || loading || loadError != null || changesInFlight) {
            return;
        }
        refreshError = null;
        publish();
        requestChanges(0);
    }

    public void loadMore() {
        if (!group || loading || loadingMore || !hasMore || nextBefore == null) {
            return;
        }
        requestPage(nextBefore, false);
    }

    public void createPoll(@NonNull PollDraft draft) {
        if (!group) {
            createError = PollRepository.Failure.NOT_A_GROUP;
            publish();
            return;
        }
        if (creating) {
            return;
        }
        Instant now = clock.instant();
        if (draft.validate(now) != PollValidation.OK) {
            createError = PollRepository.Failure.VALIDATION;
            publish();
            return;
        }
        creating = true;
        createError = null;
        createdPollId = null;
        publish();
        PollDraft normalized = draft.normalized();
        PollRepository.Cancellable request = repository.create(
                conversationId, normalized, new PollRepository.ItemCallback() {
                    @Override
                    public void onSuccess(@NonNull Poll poll) {
                        creating = false;
                        createdPollId = poll.getId();
                        loaded = true;
                        loadError = null;
                        mergePoll(poll, true);
                        selections.put(poll.getId(), new LinkedHashSet<>(poll.getMyOptionIds()));
                        lastSince = Math.max(lastSince, poll.getVersion());
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull PollRepository.Failure failure) {
                        creating = false;
                        createError = failure;
                        publish();
                    }
                });
        activeRequests.add(request);
    }

    public void consumeCreatedPoll() {
        if (createdPollId == null) {
            return;
        }
        createdPollId = null;
        publish();
    }

    /** Clears a previous create outcome before the sheet is shown again. */
    public void clearCreateState() {
        if (creating) {
            return;
        }
        createError = null;
        createdPollId = null;
        publish();
    }

    public void toggleOption(@NonNull String pollId, @NonNull String optionId) {
        Poll poll = findPoll(pollId);
        if (poll == null || isVoting(pollId)) {
            return;
        }
        if (poll.isClosed(clock.instant())) {
            events.emit(PollActionError.CLOSED);
            return;
        }
        LinkedHashSet<String> selection = currentSelection(pollId);
        if (poll.isMultipleChoice()) {
            if (!selection.remove(optionId)) {
                selection.add(optionId);
            }
        } else if (selection.contains(optionId) && selection.size() == 1) {
            selection.clear();
        } else {
            selection.clear();
            selection.add(optionId);
        }
        selections.put(pollId, selection);
        publish();
    }

    /** Submits the local draft; an empty draft retracts the saved vote. */
    public void submitVote(@NonNull String pollId) {
        Poll poll = findPoll(pollId);
        if (poll == null || votingPollId != null) {
            return;
        }
        if (poll.isClosed(clock.instant())) {
            events.emit(PollActionError.CLOSED);
            return;
        }
        List<String> selection = new ArrayList<>(currentSelection(pollId));
        if (selection.isEmpty()) {
            retractVote(pollId);
            return;
        }
        if (sameSelection(selection, poll.getMyOptionIds())) {
            return;
        }
        if (!poll.isMultipleChoice() && selection.size() != 1) {
            events.emit(PollActionError.VALIDATION);
            return;
        }
        Set<String> known = new HashSet<>();
        for (PollOption option : poll.getOptions()) {
            known.add(option.getId());
        }
        if (!known.containsAll(selection)) {
            events.emit(PollActionError.VALIDATION);
            return;
        }
        applyOptimistic(poll, selection, true);
    }

    public void retractVote(@NonNull String pollId) {
        Poll poll = findPoll(pollId);
        if (poll == null || votingPollId != null) {
            return;
        }
        if (poll.isClosed(clock.instant())) {
            events.emit(PollActionError.CLOSED);
            return;
        }
        if (poll.getMyOptionIds().isEmpty() && currentSelection(pollId).isEmpty()) {
            return;
        }
        applyOptimistic(poll, Collections.emptyList(), false);
    }

    public void closePoll(@NonNull String pollId) {
        Poll poll = findPoll(pollId);
        if (poll == null) {
            return;
        }
        if (!canManage(poll)) {
            events.emit(PollActionError.NOT_ADMIN);
            return;
        }
        PollRepository.Cancellable request = repository.close(
                conversationId, pollId, new PollRepository.ItemCallback() {
                    @Override
                    public void onSuccess(@NonNull Poll updated) {
                        mergePoll(updated, false);
                        lastSince = Math.max(lastSince, updated.getVersion());
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull PollRepository.Failure failure) {
                        events.emit(PollActionError.fromFailure(failure));
                    }
                });
        activeRequests.add(request);
    }

    public void deletePoll(@NonNull String pollId) {
        Poll poll = findPoll(pollId);
        if (poll == null) {
            return;
        }
        if (!canManage(poll)) {
            events.emit(PollActionError.NOT_ADMIN);
            return;
        }
        PollRepository.Cancellable request = repository.delete(
                conversationId, pollId, new PollRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        polls.removeIf(candidate -> candidate.getId().equals(pollId));
                        selections.remove(pollId);
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull PollRepository.Failure failure) {
                        events.emit(PollActionError.fromFailure(failure));
                    }
                });
        activeRequests.add(request);
    }

    /** Exposed for tests and for the fragment's resume timer. */
    public void refreshChanges() {
        if (!group || !loaded || loading || changesInFlight || loadError != null) {
            return;
        }
        requestChanges(0);
    }

    private void requestChanges(int page) {
        if (page >= MAX_CHANGE_PAGES_PER_REFRESH) {
            changesInFlight = false;
            publish();
            return;
        }
        changesInFlight = true;
        long since = lastSince;
        PollRepository.Cancellable request = repository.changes(
                conversationId, since, RetrofitPollRepository.CHANGES_PAGE_SIZE,
                new PollRepository.ChangesCallback() {
                    @Override
                    public void onSuccess(@NonNull PollChanges changes) {
                        applyChanges(changes);
                        if (changes.hasMore()) {
                            requestChanges(page + 1);
                        } else {
                            changesInFlight = false;
                            refreshError = null;
                            publish();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull PollRepository.Failure failure) {
                        changesInFlight = false;
                        if (!polls.isEmpty()) {
                            refreshError = failure;
                        }
                        publish();
                    }
                });
        activeRequests.add(request);
    }

    private void applyChanges(@NonNull PollChanges changes) {
        for (PollChanges.Change change : changes.getItems()) {
            if (change.getVersion() <= lastSince) {
                continue;
            }
            if (change.isDeleted()) {
                polls.removeIf(poll -> poll.getId().equals(change.getPollId()));
                selections.remove(change.getPollId());
                continue;
            }
            Poll poll = change.getPoll();
            if (poll != null) {
                mergePoll(poll, false);
            }
        }
        lastSince = Math.max(lastSince, changes.getNextSince());
    }

    private void mergePoll(@NonNull Poll incoming, boolean makeSelection) {
        Poll poll = incoming.sanitized();
        boolean found = false;
        for (int index = 0; index < polls.size(); index++) {
            if (polls.get(index).getId().equals(poll.getId())) {
                polls.set(index, poll);
                found = true;
                break;
            }
        }
        if (!found) {
            polls.add(poll);
        }
        polls.sort(NEWEST_FIRST);
        LinkedHashSet<String> selection = selections.get(poll.getId());
        boolean untouched = selection == null
                || sameSelection(selection, poll.getMyOptionIds())
                || makeSelection;
        if (untouched && !isVoting(poll.getId())) {
            selections.put(poll.getId(), new LinkedHashSet<>(poll.getMyOptionIds()));
        } else if (selection == null) {
            selections.put(poll.getId(), new LinkedHashSet<>(poll.getMyOptionIds()));
        }
    }

    private void applyOptimistic(
            @NonNull Poll previous, @NonNull List<String> selection, boolean vote) {
        Poll optimistic = previous.withSelection(selection, myUserId);
        PollRepository.ItemCallback callback = new PollRepository.ItemCallback() {
            @Override
            public void onSuccess(@NonNull Poll updated) {
                votingPollId = null;
                mergePoll(updated, true);
                selections.put(updated.getId(), new LinkedHashSet<>(updated.getMyOptionIds()));
                lastSince = Math.max(lastSince, updated.getVersion());
                publish();
            }

            @Override
            public void onFailure(@NonNull PollRepository.Failure failure) {
                votingPollId = null;
                Poll restored = failure == PollRepository.Failure.CLOSED
                        ? previous.asClosed(clock.instant())
                        : previous;
                mergePoll(restored, true);
                selections.put(restored.getId(), new LinkedHashSet<>(restored.getMyOptionIds()));
                publish();
                events.emit(PollActionError.fromFailure(failure));
                if (failure == PollRepository.Failure.CLOSED) {
                    refreshChanges();
                }
            }
        };
        replacePoll(optimistic);
        votingPollId = previous.getId();
        selections.put(previous.getId(), new LinkedHashSet<>(selection));
        publish();
        PollRepository.Cancellable request = vote
                ? repository.vote(conversationId, previous.getId(),
                        new ArrayList<>(selection), callback)
                : repository.retractVote(conversationId, previous.getId(), callback);
        activeRequests.add(request);
    }

    private void requestPage(@Nullable String before, boolean firstPage) {
        if (conversationId.isBlank()) {
            loaded = true;
            loadError = PollRepository.Failure.UNKNOWN;
            publish();
            return;
        }
        if (firstPage) {
            loading = true;
            loadError = null;
        } else {
            loadingMore = true;
        }
        publish();
        int requestGeneration = ++generation;
        PollRepository.Cancellable request = repository.load(
                conversationId, before, new PollRepository.PageCallback() {
                    @Override
                    public void onSuccess(@NonNull PollPage page) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        if (firstPage) {
                            polls.clear();
                            selections.clear();
                        }
                        for (Poll poll : page.getItems()) {
                            mergePoll(poll, false);
                        }
                        nextBefore = page.getNextBefore();
                        hasMore = page.hasMore();
                        loading = false;
                        loadingMore = false;
                        loaded = true;
                        loadError = null;
                        if (firstPage) {
                            lastSince = 0L;
                            for (Poll poll : polls) {
                                lastSince = Math.max(lastSince, poll.getVersion());
                            }
                        }
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull PollRepository.Failure failure) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        loading = false;
                        loadingMore = false;
                        loaded = true;
                        if (firstPage) {
                            loadError = failure;
                        } else {
                            events.emit(PollActionError.fromFailure(failure));
                        }
                        publish();
                    }
                });
        activeRequests.add(request);
    }

    private void replacePoll(Poll poll) {
        for (int index = 0; index < polls.size(); index++) {
            if (polls.get(index).getId().equals(poll.getId())) {
                polls.set(index, poll);
                return;
            }
        }
        polls.add(poll);
    }

    private void loadViewer() {
        if (!group) {
            return;
        }
        viewerRequest = viewerSource.load(conversationId, (members, userId) -> {
            voterNames.clear();
            myUserId = userId;
            admin = false;
            for (PollViewerSource.Member member : members) {
                if (member.getDisplayName() != null && !member.getDisplayName().isBlank()) {
                    voterNames.put(member.getUserId(), member.getDisplayName());
                }
                if (userId != null && userId.equals(member.getUserId()) && member.isAdmin()) {
                    admin = true;
                }
            }
            publish();
        });
    }

    private boolean canManage(@NonNull Poll poll) {
        return (myUserId != null && myUserId.equals(poll.getCreatedBy())) || admin;
    }

    @Nullable
    private Poll findPoll(@NonNull String pollId) {
        for (Poll poll : polls) {
            if (poll.getId().equals(pollId)) {
                return poll;
            }
        }
        return null;
    }

    private boolean isVoting(@NonNull String pollId) {
        return pollId.equals(votingPollId);
    }

    @NonNull
    private LinkedHashSet<String> currentSelection(@NonNull String pollId) {
        LinkedHashSet<String> current = selections.get(pollId);
        if (current == null) {
            Poll poll = findPoll(pollId);
            return poll == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(poll.getMyOptionIds());
        }
        return new LinkedHashSet<>(current);
    }

    private static boolean sameSelection(
            @NonNull java.util.Collection<String> left, @NonNull List<String> right) {
        return left.size() == right.size() && left.containsAll(right);
    }

    private void publish() {
        Map<String, List<String>> selectionCopy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : selections.entrySet()) {
            selectionCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        uiState.setValue(new PollUiState(
                loading, loaded, Collections.unmodifiableList(new ArrayList<>(polls)),
                loadError, refreshError, loadingMore, hasMore, nextBefore, creating, createError,
                createdPollId, votingPollId, selectionCopy, voterNames, myUserId, admin));
    }

    @Override
    protected void onCleared() {
        generation++;
        if (viewerRequest != null) {
            viewerRequest.cancel();
            viewerRequest = null;
        }
        for (PollRepository.Cancellable request : activeRequests) {
            request.cancel();
        }
        activeRequests.clear();
        super.onCleared();
    }
}
