package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable render state for the poll tab. */
public final class PollUiState {

    private final boolean loading;
    private final boolean loaded;
    private final List<Poll> polls;
    @Nullable private final PollRepository.Failure loadError;
    @Nullable private final PollRepository.Failure refreshError;
    private final boolean loadingMore;
    private final boolean hasMore;
    @Nullable private final String nextBefore;
    private final boolean creating;
    @Nullable private final PollRepository.Failure createError;
    @Nullable private final String createdPollId;
    @Nullable private final String votingPollId;
    private final Map<String, List<String>> selections;
    private final Map<String, String> voterNames;
    @Nullable private final String myUserId;
    private final boolean admin;

    public PollUiState() {
        this(false, false, Collections.emptyList(), null, null, false, false, null, false, null,
                null, null, Collections.emptyMap(), Collections.emptyMap(), null, false);
    }

    public PollUiState(
            boolean loading,
            boolean loaded,
            @NonNull List<Poll> polls,
            @Nullable PollRepository.Failure loadError,
            @Nullable PollRepository.Failure refreshError,
            boolean loadingMore,
            boolean hasMore,
            @Nullable String nextBefore,
            boolean creating,
            @Nullable PollRepository.Failure createError,
            @Nullable String createdPollId,
            @Nullable String votingPollId,
            @NonNull Map<String, List<String>> selections,
            @NonNull Map<String, String> voterNames,
            @Nullable String myUserId,
            boolean admin) {
        this.loading = loading;
        this.loaded = loaded;
        this.polls = List.copyOf(polls);
        this.loadError = loadError;
        this.refreshError = refreshError;
        this.loadingMore = loadingMore;
        this.hasMore = hasMore;
        this.nextBefore = nextBefore;
        this.creating = creating;
        this.createError = createError;
        this.createdPollId = createdPollId;
        this.votingPollId = votingPollId;
        Map<String, List<String>> copiedSelections = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : selections.entrySet()) {
            copiedSelections.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.selections = Collections.unmodifiableMap(copiedSelections);
        this.voterNames = Collections.unmodifiableMap(new LinkedHashMap<>(voterNames));
        this.myUserId = myUserId;
        this.admin = admin;
    }

    public boolean isLoading() { return loading; }
    public boolean isLoaded() { return loaded; }
    @NonNull public List<Poll> getPolls() { return polls; }
    @Nullable public PollRepository.Failure getLoadError() { return loadError; }
    @Nullable public PollRepository.Failure getRefreshError() { return refreshError; }
    public boolean isLoadingMore() { return loadingMore; }
    public boolean hasMore() { return hasMore; }
    @Nullable public String getNextBefore() { return nextBefore; }
    public boolean isCreating() { return creating; }
    @Nullable public PollRepository.Failure getCreateError() { return createError; }
    @Nullable public String getCreatedPollId() { return createdPollId; }
    @Nullable public String getVotingPollId() { return votingPollId; }
    @NonNull public Map<String, List<String>> getSelections() { return selections; }
    @NonNull public Map<String, String> getVoterNames() { return voterNames; }
    @Nullable public String getMyUserId() { return myUserId; }
    public boolean isAdmin() { return admin; }

    @NonNull
    public List<String> selection(@NonNull String pollId) {
        List<String> selection = selections.get(pollId);
        return selection == null ? Collections.emptyList() : selection;
    }

    public boolean isVoting(@NonNull String pollId) {
        return pollId.equals(votingPollId);
    }

    public boolean canManage(@NonNull Poll poll) {
        return (myUserId != null && myUserId.equals(poll.getCreatedBy())) || admin;
    }

    @Nullable
    public Poll find(@Nullable String pollId) {
        if (pollId == null) {
            return null;
        }
        for (Poll poll : polls) {
            if (poll.getId().equals(pollId)) {
                return poll;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return loaded && !loading && loadError == null && polls.isEmpty();
    }
}
