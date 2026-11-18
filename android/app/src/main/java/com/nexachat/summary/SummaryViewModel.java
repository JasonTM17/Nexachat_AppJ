package com.nexachat.summary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Named;

/** Rotation-safe request state; only the selected scope, count and summary id are saved. */
@HiltViewModel
public final class SummaryViewModel extends ViewModel {
    public static final String ARG_CONVERSATION_ID = "conversationId";
    public static final int DEFAULT_LAST_N = 50;
    private static final String KEY_SCOPE = "summary_scope";
    private static final String KEY_LIMIT = "summary_limit";
    private static final String KEY_SUMMARY_ID = "summary_last_id";

    private final SavedStateHandle savedStateHandle;
    private final SummaryRepository repository;
    private final Executor mainExecutor;
    private final LongSupplier clock;
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
        this(savedStateHandle, repository, mainExecutor, System::currentTimeMillis);
    }

    SummaryViewModel(SavedStateHandle savedStateHandle, SummaryRepository repository) {
        this(savedStateHandle, repository, Runnable::run, System::currentTimeMillis);
    }

    SummaryViewModel(
            SavedStateHandle savedStateHandle,
            SummaryRepository repository,
            Executor mainExecutor,
            LongSupplier clock) {
        this.savedStateHandle = Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastScope = restoredScope();
        this.lastLimit = restoredLimit(this.lastScope);
        this.state = new MutableLiveData<>(restoreState());
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

    /**
     * Re-requests a TODAY summary whose day or zone no longer matches the device, so a sheet
     * left open across midnight or a zone change never shows yesterday's result.
     */
    public void revalidate() {
        SummaryUiState current = state.getValue();
        if (current == null
                || current.getStatus() != SummaryUiState.Status.READY
                || current.getSummary() == null) {
            return;
        }
        SummaryResponse summary = current.getSummary();
        SummaryPeriod period = summary.getPeriod();
        if (summary.getScope() != SummaryScope.TODAY || period == null) {
            return;
        }
        String zone = TimeZone.getDefault().getID();
        if (!zone.equals(period.getTimeZone()) || !today(zone).equals(period.getDate())) {
            start(current.getScope(), current.getLimit(), false);
        }
    }

    public void dismiss() {
        requestGeneration++;
        cancelActiveRequest();
        savedStateHandle.remove(KEY_SUMMARY_ID);
        SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
        publish(requestGeneration, SummaryUiState.idle(scope, lastLimit, null));
    }

    @Override
    protected void onCleared() {
        cancelActiveRequest();
        super.onCleared();
    }

    private void start(SummaryScope scope, Integer limit, boolean refresh) {
        start(scope, limit, refresh, false);
    }

    /**
     * {@code zoneFallback} marks the single retry that omits the device zone after the server
     * rejected it, so a second rejection cannot loop.
     */
    private void start(SummaryScope scope, Integer limit, boolean refresh, boolean zoneFallback) {
        Objects.requireNonNull(scope, "scope");
        requestGeneration++;
        long token = requestGeneration;
        cancelActiveRequest();
        lastScope = scope;
        lastLimit = limit;
        savedStateHandle.set(KEY_SCOPE, scope.name());
        if (limit == null) {
            savedStateHandle.remove(KEY_LIMIT);
        } else {
            savedStateHandle.set(KEY_LIMIT, limit);
        }

        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        String lastSummaryId = savedStateHandle.get(KEY_SUMMARY_ID);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            publish(token, SummaryUiState.error(scope, limit, SummaryError.UNKNOWN, lastSummaryId));
            return;
        }

        publish(token, SummaryUiState.loading(scope, limit, lastSummaryId));
        SummaryRequest request = requestFor(scope, limit, refresh, zoneFallback);
        SummaryRepository.Cancellable handle = repository.request(conversationId, request,
                new SummaryRepository.Callback() {
                    @Override
                    public void onSuccess(SummaryResponse response) {
                        publish(token, SummaryUiState.ready(scope, limit, response));
                    }

                    @Override
                    public void onError(SummaryError error) {
                        if (error == SummaryError.INVALID_TIME_ZONE && !zoneFallback) {
                            start(scope, limit, refresh, true);
                            return;
                        }
                        publish(token, SummaryUiState.error(scope, limit,
                                error == SummaryError.INVALID_TIME_ZONE
                                        ? SummaryError.UNKNOWN
                                        : error,
                                lastSummaryId));
                    }
                });
        if (token == requestGeneration) {
            activeRequest = handle;
        }
    }

    private SummaryRequest requestFor(SummaryScope scope, Integer limit, boolean refresh) {
        return requestFor(scope, limit, refresh, false);
    }

    private SummaryRequest requestFor(
            SummaryScope scope, Integer limit, boolean refresh, boolean zoneFallback) {
        if (zoneFallback) {
            return SummaryRequest.withoutTimeZone(scope, limit, SummaryLanguage.AUTO, refresh);
        }
        return new SummaryRequest(scope, limit, SummaryLanguage.AUTO, refresh,
                TimeZone.getDefault().getID());
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
        Integer limit = restoredLimit(scope);
        return repository.findCachedById(conversationId, requestFor(scope, limit, false), summaryId)
                .map(summary -> SummaryUiState.ready(scope, limit, summary))
                .orElseGet(() -> SummaryUiState.idle(scope, limit, summaryId));
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

    private Integer restoredLimit(SummaryScope scope) {
        if (scope != SummaryScope.LAST_N) {
            return null;
        }
        Integer saved = savedStateHandle.get(KEY_LIMIT);
        return saved == null ? DEFAULT_LAST_N : saved;
    }

    private String today(String zone) {
        return Instant.ofEpochMilli(clock.getAsLong())
                .atZone(ZoneId.of(zone))
                .toLocalDate()
                .toString();
    }

    private void cancelActiveRequest() {
        if (activeRequest != null) {
            activeRequest.cancel();
            activeRequest = null;
        }
    }
}
