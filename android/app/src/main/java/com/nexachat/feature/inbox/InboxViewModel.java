package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

/**
 * Backs InboxScreen. Room remains the render source; repository refreshes run
 * off the UI thread and update the inbox stream.
 */
@HiltViewModel
public final class InboxViewModel extends ViewModel {

    private static final String KEY_SELECTED_FILTER = "inbox_selected_filter";

    private final SavedStateHandle savedStateHandle;
    private final ConversationRepository conversationRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<InboxUiState> mutableUiState =
            new MutableLiveData<>(new InboxUiState());
    private final InboxSingleLiveEvent<InboxEvent> events = new InboxSingleLiveEvent<>();
    private InboxUiState currentState = mutableUiState.getValue();
    private boolean refreshInFlight;
    @Nullable private Boolean pendingVariantRefresh;
    private boolean nextPageInFlight;
    private long pageOperationGeneration;

    @Inject
    public InboxViewModel(
            SavedStateHandle savedStateHandle,
            ConversationRepository conversationRepository,
            @Nullable TokenStore tokenStore,
            @Nullable Moshi moshi) {
        this(savedStateHandle, conversationRepository);
        start(tokenStore, moshi);
    }

    /** Test constructor — no token/JWT resolution. */
    public InboxViewModel(
            ConversationRepository conversationRepository,
            SavedStateHandle savedStateHandle) {
        this(savedStateHandle, conversationRepository);
        start(null, null);
    }

    /** Test constructor — no token/JWT resolution or saved filter state. */
    public InboxViewModel(ConversationRepository conversationRepository) {
        this(conversationRepository, new SavedStateHandle());
    }

    private InboxViewModel(
            SavedStateHandle savedStateHandle,
            ConversationRepository conversationRepository) {
        this.savedStateHandle =
                Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        InboxFilter restoredFilter = InboxFilter.fromSavedValue(
                savedStateHandle.get(KEY_SELECTED_FILTER));
        currentState = new InboxUiState().withSelectedFilter(restoredFilter);
        mutableUiState.setValue(currentState);
    }

    public LiveData<InboxUiState> getUiState() {
        return mutableUiState;
    }

    /** One-shot preference failures for the inbox surface. */
    public LiveData<InboxSingleLiveEvent.Event<InboxEvent>> getEvents() {
        return events;
    }

    public void selectFilter(@NonNull InboxFilter filter) {
        Objects.requireNonNull(filter, "filter");
        InboxFilter previous = currentState.getSelectedFilter();
        savedStateHandle.set(KEY_SELECTED_FILTER, filter.name());
        updateState(state -> state.withSelectedFilter(filter));
        if (filter == previous) {
            return;
        }
        if (filter == InboxFilter.ARCHIVED) {
            refreshVariant(true);
        } else if (previous == InboxFilter.ARCHIVED) {
            refreshVariant(false);
        }
    }

    /** Pulls the authoritative inbox from REST (launch + user refresh). */
    public synchronized void refresh() {
        refreshVariant(currentState.getSelectedFilter() == InboxFilter.ARCHIVED);
    }

    private synchronized void refreshVariant(boolean archived) {
        if (refreshInFlight) {
            pendingVariantRefresh = archived;
            return;
        }
        refreshInFlight = true;
        pendingVariantRefresh = null;
        long generation = ++pageOperationGeneration;
        updateState(InboxUiState::withRefreshStarted);
        disposables.add(conversationRepository.refreshInboxPage(archived)
                .subscribeOn(Schedulers.io())
                .doFinally(() -> {
                    Boolean pending;
                    synchronized (InboxViewModel.this) {
                        refreshInFlight = false;
                        pending = pendingVariantRefresh;
                        pendingVariantRefresh = null;
                    }
                    if (pending != null) {
                        refreshVariant(pending);
                    }
                })
                .subscribe(
                        result -> {
                            if (!isPageOperationCurrent(generation)) return;
                            if (result instanceof Result.Success<?> success) {
                                updateState(state -> state.withRefreshResult(
                                        Boolean.TRUE.equals(success.getValue())));
                            } else {
                                updateState(state -> state.withRefreshFailure(
                                        ((Result.Failure<?>) result).getError()));
                            }
                        },
                        error -> {
                            if (isPageOperationCurrent(generation)) {
                                updateState(state -> state.withRefreshFailure(
                                        new ApiError.Network(error.getMessage())));
                            }
                        }));
    }

    /** Loads the next inbox page once the visible list approaches its end. */
    public synchronized void loadNextPage() {
        if (refreshInFlight || nextPageInFlight || !currentState.hasMore()
                || currentState.isLoadingMore()) {
            return;
        }
        nextPageInFlight = true;
        long generation = pageOperationGeneration;
        updateState(InboxUiState::withNextPageStarted);
        disposables.add(conversationRepository.loadNextInboxPage()
                .subscribeOn(Schedulers.io())
                .doFinally(() -> {
                    boolean stale;
                    synchronized (InboxViewModel.this) {
                        nextPageInFlight = false;
                        stale = generation != pageOperationGeneration;
                    }
                    if (stale) {
                        updateState(InboxUiState::withNextPageIdle);
                    }
                })
                .subscribe(
                        result -> {
                            if (!isPageOperationCurrent(generation)) return;
                            if (result instanceof Result.Success<?> success) {
                                updateState(state -> state.withNextPageResult(
                                        Boolean.TRUE.equals(success.getValue())));
                            } else {
                                updateState(state -> state.withNextPageFailure(
                                        ((Result.Failure<?>) result).getError()));
                            }
                        },
                        error -> {
                            if (isPageOperationCurrent(generation)) {
                                updateState(state -> state.withNextPageFailure(
                                        new ApiError.Network(error.getMessage())));
                            }
                        }));
    }

    /** Mutes one conversation for the given preset instant. */
    public void muteConversation(@NonNull String conversationId, @NonNull java.time.Instant until) {
        runPreference(conversationRepository.muteConversation(conversationId, until));
    }

    /** Mutes one conversation until the caller turns notifications back on. */
    public void muteConversationForever(@NonNull String conversationId) {
        runPreference(conversationRepository.muteConversationForever(conversationId));
    }

    /** Clears the mute in one tap. */
    public void unmuteConversation(@NonNull String conversationId) {
        runPreference(conversationRepository.unmuteConversation(conversationId));
    }

    /** Archives or restores one conversation for the caller. */
    public void setConversationArchived(@NonNull String conversationId, boolean archived) {
        runPreference(conversationRepository.setConversationArchived(conversationId, archived));
    }

    private void runPreference(Single<Result<Void>> request) {
        disposables.add(Objects.requireNonNull(request, "request")
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Failure<?> failure) {
                                events.postEvent(new InboxEvent(failure.getError()));
                            }
                        },
                        error -> events.postEvent(
                                new InboxEvent(new ApiError.Network(error.getMessage())))));
    }

    private synchronized boolean isPageOperationCurrent(long generation) {
        return generation == pageOperationGeneration;
    }

    private void start(@Nullable TokenStore tokenStore, @Nullable Moshi moshi) {
        if (tokenStore != null && moshi != null) {
            disposables.add(tokenStore.readTokens()
                    .map(tokens -> Optional.ofNullable(
                            TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi)))
                    .defaultIfEmpty(Optional.empty())
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            userId -> updateState(state -> state.withCurrentUserId(
                                    userId.orElse(null))),
                            error -> updateState(state -> state.withCurrentUserId(null))));
        }

        disposables.add(conversationRepository.inboxFlow()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        conversations -> updateState(state ->
                                state.withConversations(conversations, false)),
                        error -> updateState(state -> state.withError(
                                new ApiError.Network(error.getMessage())))));
        refresh();
    }

    private synchronized void updateState(UnaryOperator<InboxUiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}

/** One-shot inbox preference failure; consumed once so rotation does not replay it. */
final class InboxEvent {
    private final ApiError error;

    InboxEvent(ApiError error) {
        this.error = Objects.requireNonNull(error, "error");
    }

    ApiError getError() {
        return error;
    }
}

/** LiveData event wrapper: an event is consumed once. */
final class InboxSingleLiveEvent<T> extends MutableLiveData<InboxSingleLiveEvent.Event<T>> {
    private long sequence;

    void postEvent(T value) {
        postValue(new Event<>(++sequence, value));
    }

    static final class Event<T> {
        private final long sequence;
        private final T value;
        private boolean consumed;

        Event(long sequence, T value) {
            this.sequence = sequence;
            this.value = value;
        }

        long getSequence() {
            return sequence;
        }

        @Nullable
        synchronized T consume() {
            if (consumed) {
                return null;
            }
            consumed = true;
            return value;
        }
    }
}
