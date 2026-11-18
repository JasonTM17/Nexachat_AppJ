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

/** Paged blocked-user list with unblock actions. */
@HiltViewModel
public final class BlockedUsersViewModel extends ViewModel {

    public static final int PAGE_SIZE = 50;

    public static final class UiState {
        private final List<SafetyRepository.BlockedUser> items;
        private final boolean loading;
        private final boolean error;
        @Nullable private final String nextCursor;

        UiState(
                List<SafetyRepository.BlockedUser> items,
                boolean loading,
                boolean error,
                @Nullable String nextCursor) {
            this.items = items;
            this.loading = loading;
            this.error = error;
            this.nextCursor = nextCursor;
        }

        @NonNull public List<SafetyRepository.BlockedUser> getItems() {
            return items;
        }

        public boolean isLoading() {
            return loading;
        }

        public boolean isError() {
            return error;
        }

        public boolean hasMore() {
            return nextCursor != null;
        }

        @Nullable String getNextCursor() {
            return nextCursor;
        }
    }

    private final SafetyRepository safetyRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState =
            new MutableLiveData<>(new UiState(List.of(), false, false, null));
    private int listGeneration;

    @Inject
    public BlockedUsersViewModel(SafetyRepository safetyRepository) {
        this.safetyRepository = safetyRepository;
        loadFirstPage();
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

    public void unblock(@NonNull String userId) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        listGeneration++;
        List<SafetyRepository.BlockedUser> remaining = new ArrayList<>();
        for (SafetyRepository.BlockedUser row : state.items) {
            if (!userId.equals(row.userId())) {
                remaining.add(row);
            }
        }
        uiState.setValue(new UiState(remaining, state.loading, state.error, state.nextCursor));
        disposables.add(safetyRepository.unblockUser(userId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, ignored -> refresh()));
    }

    public void refresh() {
        listGeneration++;
        uiState.setValue(new UiState(List.of(), true, false, null));
        loadFirstPage();
    }

    private void loadFirstPage() {
        UiState state = uiState.getValue();
        if (state != null && !state.loading) {
            uiState.setValue(new UiState(state.items, true, false, state.nextCursor));
        }
        loadPage(null);
    }

    private void loadPage(@Nullable String cursor) {
        int requestGeneration = listGeneration;
        disposables.add(safetyRepository.listBlocked(PAGE_SIZE, cursor)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    if (requestGeneration != listGeneration) {
                        return;
                    }
                    UiState state = uiState.getValue();
                    List<SafetyRepository.BlockedUser> merged = new ArrayList<>();
                    if (cursor != null && state != null) {
                        merged.addAll(state.items);
                    }
                    merged.addAll(page.items());
                    uiState.setValue(new UiState(merged, false, false, page.nextCursor()));
                }, error -> {
                    if (requestGeneration != listGeneration) {
                        return;
                    }
                    UiState state = uiState.getValue();
                    uiState.setValue(new UiState(
                            state == null ? List.of() : state.items, false, true, null));
                }));
    }

    @Override protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
