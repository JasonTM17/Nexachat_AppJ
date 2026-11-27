package com.nexachat.summary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Named;

/** Rotation-safe request state; saves the selected mode, scope, count and summary id. */
@HiltViewModel
public final class SummaryViewModel extends ViewModel {
    public enum Mode { AUTOMATIC, LOCAL, AI }

    public static final String ARG_CONVERSATION_ID = "conversationId";
    public static final int DEFAULT_LAST_N = 50;
    private static final String KEY_SCOPE = "summary_scope";
    private static final String KEY_LIMIT = "summary_limit";
    private static final String KEY_SUMMARY_ID = "summary_last_id";
    private static final String KEY_MODE = "summary_mode";

    private final SavedStateHandle savedStateHandle;
    private final SummaryRepository repository;
    private final Executor mainExecutor;
    private final LongSupplier clock;
    private final MutableLiveData<SummaryUiState> state;

    private SummaryScope lastScope;
    private Integer lastLimit;
    private Mode mode;
    private String boundConversationId;
    // Checkbox state of the rendered action items, keyed by action key. Living on the
    // ViewModel keeps it across rebinds and rotation; the sheet's death drops it, so a
    // freshly opened summary always starts unchecked.
    private final Map<String, Boolean> actionItemChecks = new HashMap<>();
    private final AtomicLong requestGeneration = new AtomicLong();
    private long boundCacheEpoch;
    private final SummaryRepository.Cancellable cacheInvalidationSubscription;
    private volatile boolean cleared;
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
        this.mode = restoredMode();
        this.boundConversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        this.boundCacheEpoch = repository.cacheEpoch();
        this.state = new MutableLiveData<>(restoreState());
        this.cacheInvalidationSubscription = repository.onCacheInvalidated(this::onCacheInvalidated);
        if (boundCacheEpoch != repository.cacheEpoch()) {
            onCacheInvalidated(null);
        }
    }

    public LiveData<SummaryUiState> state() {
        return state;
    }

    /** Mutable checkbox state shared with the sheet binder; keyed by action item key. */
    public Map<String, Boolean> actionItemChecks() {
        return actionItemChecks;
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

    public Mode mode() {
        return mode;
    }

    public void refreshWithMode(Mode selection) {
        Objects.requireNonNull(selection, "selection");
        if (mode != selection) {
            savedStateHandle.remove(KEY_SUMMARY_ID);
            mode = selection;
            savedStateHandle.set(KEY_MODE, selection.name());
        }
        refresh();
    }

    /**
     * Re-requests a TODAY summary whose day or zone no longer matches the device, so a sheet
     * left open across midnight or a zone change never shows yesterday's result. A summary the
     * server resolved in its own default zone already covers the server's day, so it is kept.
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
        if (summary.getScope() != SummaryScope.TODAY
                || period == null
                || period.isTimeZoneDefaulted()) {
            return;
        }
        String zone = TimeZone.getDefault().getID();
        if (!zone.equals(period.getTimeZone()) || !today(zone).equals(period.getDate())) {
            start(current.getScope(), current.getLimit(), false);
        }
    }

    public void dismiss() {
        long token = requestGeneration.incrementAndGet();
        cancelActiveRequest();
        savedStateHandle.remove(KEY_SUMMARY_ID);
        SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
        publish(token, savedStateHandle.get(ARG_CONVERSATION_ID),
                () -> applyState(SummaryUiState.idle(scope, lastLimit, null)));
    }

    @Override
    protected void onCleared() {
        cleared = true;
        requestGeneration.incrementAndGet();
        cancelActiveRequest();
        if (cacheInvalidationSubscription != null) {
            cacheInvalidationSubscription.cancel();
        }
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
        long token = requestGeneration.incrementAndGet();
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
        long epoch = repository.cacheEpoch();
        if (!Objects.equals(boundConversationId, conversationId) || boundCacheEpoch != epoch) {
            boundConversationId = conversationId;
            boundCacheEpoch = epoch;
            savedStateHandle.remove(KEY_SUMMARY_ID);
        }
        String lastSummaryId = savedStateHandle.get(KEY_SUMMARY_ID);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            publish(token, conversationId,
                    () -> applyState(SummaryUiState.error(scope, limit, SummaryError.UNKNOWN, lastSummaryId)));
            return;
        }

        publish(token, conversationId,
                () -> applyState(SummaryUiState.loading(scope, limit, lastSummaryId)));
        // LiveData observers may synchronously dismiss or supersede a loading request.
        if (!isCurrent(token, conversationId)) {
            return;
        }
        SummaryRequest request = requestFor(scope, limit, refresh, zoneFallback);
        AtomicBoolean completed = new AtomicBoolean();
        SummaryRepository.Callback callback = new SummaryRepository.Callback() {
            @Override
            public void onSuccess(SummaryResponse response) {
                publish(token, conversationId, () -> {
                    completed.set(true);
                    activeRequest = null;
                    applyState(SummaryUiState.ready(scope, limit, response));
                });
            }

            @Override
            public void onError(SummaryError error) {
                publish(token, conversationId, () -> {
                    completed.set(true);
                    activeRequest = null;
                    if (error == SummaryError.INVALID_TIME_ZONE && !zoneFallback) {
                        start(scope, limit, refresh, true);
                        return;
                    }
                    applyState(SummaryUiState.error(scope, limit,
                            error == SummaryError.INVALID_TIME_ZONE
                                    ? SummaryError.UNKNOWN : error,
                            lastSummaryId));
                });
            }
        };
        SummaryRepository.Cancellable handle = mode == Mode.LOCAL
                ? repository.requestLocally(conversationId, request, callback)
                : repository.request(conversationId, request, callback);
        if (isCurrent(token, conversationId) && !completed.get()) {
            activeRequest = handle;
        } else {
            handle.cancel();
        }
    }

    private SummaryRequest requestFor(SummaryScope scope, Integer limit, boolean refresh) {
        return requestFor(scope, limit, refresh, false);
    }

    private SummaryRequest requestFor(
            SummaryScope scope, Integer limit, boolean refresh, boolean zoneFallback) {
        if (zoneFallback) {
            // The fallback repeats the same request minus the rejected zone: an
            // explicit refresh keeps forcing a fresh generation.
            return SummaryRequest.withoutTimeZone(scope, limit, SummaryLanguage.AUTO, refresh);
        }
        return new SummaryRequest(scope, limit, SummaryLanguage.AUTO, refresh,
                TimeZone.getDefault().getID());
    }

    private boolean isCurrent(long token, String conversationId) {
        return !cleared && token == requestGeneration.get()
                && Objects.equals(conversationId, savedStateHandle.get(ARG_CONVERSATION_ID));
    }

    private void publish(long token, String conversationId, Runnable update) {
        long epoch = repository.cacheEpoch();
        mainExecutor.execute(() -> {
            // Share the repository monitor so a cache clear and actual publication are ordered.
            synchronized (repository) {
                if (isCurrent(token, conversationId) && epoch == repository.cacheEpoch()) {
                    update.run();
                }
            }
        });
    }

    private void onCacheInvalidated(SummaryError reason) {
        if (cleared) {
            return;
        }
        // This fence is immediate even when invalidation arrives on a network/session thread.
        long token = requestGeneration.incrementAndGet();
        mainExecutor.execute(() -> {
            synchronized (repository) {
                if (cleared || token != requestGeneration.get()) {
                    return;
                }
                cancelActiveRequest();
                boundCacheEpoch = repository.cacheEpoch();
                savedStateHandle.remove(KEY_SUMMARY_ID);
                SummaryScope scope = lastScope == null ? restoredScope() : lastScope;
                applyState(reason == SummaryError.NOT_MEMBER
                        ? SummaryUiState.error(scope, lastLimit, reason, null)
                        : SummaryUiState.idle(scope, lastLimit, null));
            }
        });
    }

    private void applyState(SummaryUiState nextState) {
        if (nextState.getStatus() == SummaryUiState.Status.READY
                && nextState.getSummary() != null) {
            savedStateHandle.set(KEY_SCOPE, nextState.getScope().name());
            savedStateHandle.set(KEY_SUMMARY_ID, nextState.getSummary().getSummaryId());
        }
        state.setValue(nextState);
    }

    private SummaryUiState restoreState() {
        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        String summaryId = savedStateHandle.get(KEY_SUMMARY_ID);
        SummaryScope scope = restoredScope();
        Integer limit = restoredLimit(scope);
        SummaryRequest request = requestFor(scope, limit, false);
        Optional<SummaryResponse> cached = mode == Mode.LOCAL
                ? repository.findCachedLocalById(conversationId, request, summaryId)
                : repository.findCachedById(conversationId, request, summaryId);
        return cached
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

    private Mode restoredMode() {
        String value = savedStateHandle.get(KEY_MODE);
        if (value != null) {
            try {
                return Mode.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Treat saved values from earlier app versions as the default mode.
            }
        }
        return Mode.AUTOMATIC;
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
