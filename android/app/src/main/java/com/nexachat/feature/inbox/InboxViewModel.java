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
    public void refresh() {
        disposables.add(conversationRepository.refreshInbox()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> result instanceof Result.Success<?>
                                ? state.withError(null)
                                : state.withError(((Result.Failure<?>) result).getError())),
                        error -> updateState(state -> state.withError(
                                new ApiError.Network(error.getMessage())))));
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
