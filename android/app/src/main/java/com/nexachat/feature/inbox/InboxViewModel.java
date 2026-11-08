package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.lifecycle.HiltViewModel;
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

    private final ConversationRepository conversationRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<InboxUiState> mutableUiState =
            new MutableLiveData<>(new InboxUiState());
    private InboxUiState currentState = mutableUiState.getValue();
    private boolean refreshInFlight;
    private boolean nextPageInFlight;
    private long pageOperationGeneration;

    @Inject
    public InboxViewModel(
            ConversationRepository conversationRepository,
            @Nullable TokenStore tokenStore,
            @Nullable Moshi moshi) {
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        start(tokenStore, moshi);
    }

    /** Test constructor — no token/JWT resolution. */
    public InboxViewModel(ConversationRepository conversationRepository) {
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        start(null, null);
    }

    public LiveData<InboxUiState> getUiState() {
        return mutableUiState;
    }

    /** Pulls the authoritative inbox from REST (launch + user refresh). */
    public synchronized void refresh() {
        if (refreshInFlight) return;
        refreshInFlight = true;
        long generation = ++pageOperationGeneration;
        updateState(InboxUiState::withRefreshStarted);
        disposables.add(conversationRepository.refreshInboxPage()
                .subscribeOn(Schedulers.io())
                .doFinally(() -> {
                    synchronized (InboxViewModel.this) {
                        refreshInFlight = false;
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
