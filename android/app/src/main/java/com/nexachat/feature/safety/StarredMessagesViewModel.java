package com.nexachat.feature.safety;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/** Paged starred-message list, newest star first. */
@HiltViewModel
public final class StarredMessagesViewModel extends ViewModel {

    public static final int PAGE_SIZE = 50;

    public static final class UiState {
        private final List<SafetyRepository.StarredMessage> items;
        private final boolean loading;
        private final boolean error;
        @Nullable private final String nextCursor;

        UiState(
                List<SafetyRepository.StarredMessage> items,
                boolean loading,
                boolean error,
                @Nullable String nextCursor) {
            this.items = items;
            this.loading = loading;
            this.error = error;
            this.nextCursor = nextCursor;
        }

        @NonNull public List<SafetyRepository.StarredMessage> getItems() {
            return items;
        }

        public boolean isLoading() {
            return loading;
        }

        public boolean isError() {
            return error;
        }

        @Nullable String getNextCursor() {
            return nextCursor;
        }
    }

    private final SafetyRepository safetyRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState =
            new MutableLiveData<>(new UiState(List.of(), true, false, null));

    @Inject
    public StarredMessagesViewModel(SafetyRepository safetyRepository) {
        this.safetyRepository = safetyRepository;
        loadPage(null);
    }

    @NonNull public LiveData<UiState> getUiState() {
        return uiState;
    }

    public void loadMore() {
        UiState state = uiState.getValue();
        if (state == null || state.loading || state.nextCursor == null) {
            return;
        }
        loadPage(state.nextCursor);
    }

    /** Reloads from scratch after a failure (error state retry). */
    public void retry() {
        UiState state = uiState.getValue();
        if (state != null && state.loading) {
            return;
        }
        uiState.setValue(new UiState(List.of(), true, false, null));
        loadPage(null);
    }

    /** Clears the one-shot error flag after the UI has surfaced it. */
    public void consumeError() {
        UiState state = uiState.getValue();
        if (state != null && state.error) {
            uiState.setValue(new UiState(state.items, state.loading, false, state.nextCursor));
        }
    }

    private void loadPage(@Nullable String cursor) {
        disposables.add(safetyRepository.listStarred(PAGE_SIZE, cursor)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    UiState state = uiState.getValue();
                    List<SafetyRepository.StarredMessage> merged = new ArrayList<>();
                    if (cursor != null && state != null) {
                        merged.addAll(state.items);
                    }
                    merged.addAll(page.items());
                    uiState.setValue(new UiState(merged, false, false, page.nextCursor()));
                }, error -> {
                    UiState state = uiState.getValue();
                    // Keep nextCursor so a failed next-page load can be retried.
                    uiState.setValue(new UiState(
                            state == null ? List.of() : state.items, false, true,
                            state == null ? null : state.nextCursor));
                }));
    }

    @Override protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
