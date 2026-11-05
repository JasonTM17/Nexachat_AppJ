package com.nexachat.summary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;

/** Rotation-safe request state; only the selected scope and summary id are saved. */
@HiltViewModel
public final class SummaryViewModel extends ViewModel {
    public static final String ARG_CONVERSATION_ID = "conversationId";
    private static final String KEY_SCOPE = "summary_scope";
    private static final String KEY_SUMMARY_ID = "summary_last_id";

    private final SavedStateHandle savedStateHandle;
    private final SummaryRepository repository;
    private final Executor mainExecutor;
    private final MutableLiveData<SummaryUiState> state;

    private SummaryScope lastScope;
    private Integer lastLimit;
    private long requestGeneration;
    private SummaryRepository.Cancellable activeRequest;

    @Inject
    public SummaryViewModel(
            SavedStateHandle savedStateHandle,
            SummaryRepository repository,
            @Named("summary_main") Executor mainExecutor) {
        this.savedStateHandle = Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.lastScope = restoredScope();
        this.state = new MutableLiveData<>(restoreState());
    }

    SummaryViewModel(SavedStateHandle savedStateHandle, SummaryRepository repository) {
        this(savedStateHandle, repository, Runnable::run);
    }

    public LiveData<SummaryUiState> state() {
        return state;
    }

    public void request(SummaryScope scope, Integer limit) {
        start(scope, limit, false);
    }

    public void retry() {
        SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
        if (scope != null) {
            start(scope, lastLimit, false);
        }
    }

    public void refresh() {
        SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
        if (scope != null) {
            start(scope, lastLimit, true);
        }
    }

    public void dismiss() {
        requestGeneration++;
        cancelActiveRequest();
        savedStateHandle.remove(KEY_SUMMARY_ID);
        SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
        publish(requestGeneration, SummaryUiState.idle(scope, null));
    }

    @Override
    protected void onCleared() {
        cancelActiveRequest();
        super.onCleared();
    }

    private void start(SummaryScope scope, Integer limit, boolean refresh) {
        Objects.requireNonNull(scope, "scope");
        requestGeneration++;
        long token = requestGeneration;
        cancelActiveRequest();
        lastScope = scope;
        lastLimit = limit;
        savedStateHandle.set(KEY_SCOPE, scope.name());

        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        String lastSummaryId = savedStateHandle.get(KEY_SUMMARY_ID);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            publish(token, SummaryUiState.error(scope, SummaryError.UNKNOWN, lastSummaryId));
            return;
        }

        publish(token, SummaryUiState.loading(scope, lastSummaryId));
        SummaryRequest request = new SummaryRequest(scope, limit, SummaryLanguage.AUTO, refresh);
        activeRequest = repository.request(conversationId, request, new SummaryRepository.Callback() {
            @Override
            public void onSuccess(SummaryResponse response) {
                publish(token, SummaryUiState.ready(scope, response));
            }

            @Override
            public void onError(SummaryError error) {
                publish(token, SummaryUiState.error(scope, error, lastSummaryId));
            }
        });
    }

    private void publish(long token, SummaryUiState nextState) {
        mainExecutor.execute(() -> {
            if (token != requestGeneration) {
                return;
            }
            if (nextState.getStatus() == SummaryUiState.Status.READY
                    && nextState.getSummary() != null) {
                savedStateHandle.set(KEY_SCOPE, nextState.getScope().name());
                savedStateHandle.set(KEY_SUMMARY_ID, nextState.getSummary().getSummaryId());
            }
            state.setValue(nextState);
        });
    }

    private SummaryUiState restoreState() {
        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        String summaryId = savedStateHandle.get(KEY_SUMMARY_ID);
        SummaryScope scope = restoredScope();
        return repository.findCachedById(conversationId, scope, summaryId)
                .map(summary -> SummaryUiState.ready(scope, summary))
                .orElseGet(() -> SummaryUiState.idle(scope, summaryId));
    }

    private SummaryScope restoredScope() {
        String value = savedStateHandle.get(KEY_SCOPE);
        if (value == null) {
            return SummaryScope.UNREAD;
        }
        try {
            return SummaryScope.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return SummaryScope.UNREAD;
        }
    }

    private void cancelActiveRequest() {
        if (activeRequest != null) {
            activeRequest.cancel();
            activeRequest = null;
        }
    }
}
