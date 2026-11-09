package com.nexachat.feature.label;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.network.SessionEvents;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;

/** Owns label CRUD, per-conversation assignment, and server-backed filter state. */
@HiltViewModel
public final class LabelViewModel extends ViewModel {
    private static final String KEY_SELECTED_FILTER = "inbox_selected_label_id";

    private final SavedStateHandle savedStateHandle;
    private final LabelRepository repository;
    private final Thread ownerThread;
    @Nullable private Scheduler mainThreadScheduler;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final CompositeDisposable mainThreadDispatches = new CompositeDisposable();
    @Nullable private Disposable sessionEventsSubscription;
    private final MutableLiveData<LabelUiState> uiState = new MutableLiveData<>();
    private List<ConversationLabel> labels = List.of();
    private boolean labelsLoading;
    private boolean labelsLoaded;
    @Nullable private String selectedFilterId;
    private Set<String> filteredConversationIds = Set.of();
    private boolean filterLoading;
    private LabelUiState.Error filterError = LabelUiState.Error.NONE;
    private long filterGeneration;
    @Nullable private String assignmentConversationId;
    private List<String> assignmentIds = List.of();
    private boolean assignmentLoading;
    private boolean assignmentSaving;
    private boolean assignmentMutationInFlight;
    @Nullable private String assignmentMutationConversationId;
    private List<String> assignmentMutationIds = List.of();
    private long assignmentGeneration;
    private boolean labelsMutating;
    private LabelUiState.Error error = LabelUiState.Error.NONE;
    private long errorVersion;
    private long sessionGeneration;
    private boolean started;

    @Inject
    public LabelViewModel(
            SavedStateHandle savedStateHandle,
            LabelRepository repository,
            SessionEvents sessionEvents) {
        this(repository, savedStateHandle, sessionEvents, AndroidSchedulers.mainThread());
        load();
    }

    /** Test constructor. */
    public LabelViewModel(LabelRepository repository, SavedStateHandle savedStateHandle) {
        this.savedStateHandle = Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ownerThread = Thread.currentThread();
        selectedFilterId = savedStateHandle.get(KEY_SELECTED_FILTER);
        publish();
    }

    /** Test constructor with the production session invalidation behavior. */
    public LabelViewModel(
            LabelRepository repository,
            SavedStateHandle savedStateHandle,
            SessionEvents sessionEvents) {
        this(repository, savedStateHandle, sessionEvents, Schedulers.trampoline());
    }

    /** Test constructor with an explicit scheduler for deterministic off-thread logout delivery. */
    public LabelViewModel(
            LabelRepository repository,
            SavedStateHandle savedStateHandle,
            SessionEvents sessionEvents,
            Scheduler mainThreadScheduler) {
        this(repository, savedStateHandle);
        this.mainThreadScheduler = Objects.requireNonNull(
                mainThreadScheduler, "mainThreadScheduler");
        sessionEventsSubscription = Objects.requireNonNull(sessionEvents, "sessionEvents")
                .getEvents()
                .subscribe(event -> {
                    if (event instanceof SessionEvents.Event.LoggedOut) {
                        if (Thread.currentThread() == ownerThread) {
                            resetForLoggedOutSession();
                        } else {
                            dispatchToOwnerThread(this::resetForLoggedOutSession);
                        }
                    }
                });
    }

    public LabelViewModel(LabelRepository repository) {
        this(repository, new SavedStateHandle());
    }

    public LiveData<LabelUiState> getUiState() { return uiState; }

    public synchronized void load() {
        if (started) return;
        started = true;
        loadLabelsLocked();
        if (selectedFilterId != null) loadFilterLocked(selectedFilterId);
    }

    public synchronized void reloadLabels() {
        if (!labelsLoading) loadLabelsLocked();
    }

    public synchronized void selectFilter(@Nullable String labelId) {
        if (Objects.equals(selectedFilterId, labelId)) return;
        if (labelId != null && labels.stream().noneMatch(label -> labelId.equals(label.getId()))) {
            setError(LabelUiState.Error.GENERIC);
            return;
        }
        selectedFilterId = labelId;
        savedStateHandle.set(KEY_SELECTED_FILTER, labelId);
        filteredConversationIds = Set.of();
        filterLoading = false;
        filterError = LabelUiState.Error.NONE;
        filterGeneration++;
        clearError();
        publish();
        if (labelId != null) loadFilterLocked(labelId);
    }

    public synchronized void retrySelectedFilter() {
        if (selectedFilterId != null && !filterLoading) loadFilterLocked(selectedFilterId);
    }

    public synchronized void loadAssignment(@NonNull String conversationId) {
        long requestSessionGeneration = sessionGeneration;
        long requestAssignmentGeneration = ++assignmentGeneration;
        assignmentConversationId = conversationId;
        assignmentSaving = assignmentMutationInFlight;
        if (assignmentMutationInFlight
                && conversationId.equals(assignmentMutationConversationId)) {
            assignmentIds = assignmentMutationIds;
            assignmentLoading = false;
            publish();
            return;
        }
        assignmentIds = List.of();
        assignmentLoading = true;
        clearError();
        publish();
        disposables.add(repository.cachedLabelIds(conversationId)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        ids -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration
                                        || requestAssignmentGeneration != assignmentGeneration) return;
                                if (!conversationId.equals(assignmentConversationId)) return;
                                assignmentIds = knownAssignments(ids);
                                assignmentLoading = false;
                                publish();
                            }
                        },
                        failure -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration
                                        || requestAssignmentGeneration != assignmentGeneration) return;
                                if (!conversationId.equals(assignmentConversationId)) return;
                                assignmentLoading = false;
                                setError(errorFor(failure));
                            }
                        }));
    }

    public synchronized void toggleAssignment(
            @NonNull String conversationId, @NonNull String labelId, boolean assign) {
        if (assignmentLoading || assignmentSaving || assignmentMutationInFlight || labelsMutating
                || !conversationId.equals(assignmentConversationId)) return;
        ConversationLabel label = findLabel(labelId);
        if (label == null || label.isPending()) return;

        List<String> previous = assignmentIds;
        if (assign && previous.contains(labelId)) return;
        if (!assign && !previous.contains(labelId)) return;
        if (assign && previous.size() >= LabelRules.MAX_PER_CONVERSATION) {
            setError(LabelUiState.Error.ASSIGNMENT_LIMIT);
            return;
        }

        List<String> updated = new ArrayList<>(previous);
        if (assign) updated.add(labelId);
        else updated.remove(labelId);
        boolean updatesSelectedFilter = labelId.equals(selectedFilterId);
        boolean filterWasLoading = updatesSelectedFilter && filterLoading;
        Set<String> previousFilteredIds = filteredConversationIds;
        if (updatesSelectedFilter && filterWasLoading) {
            filterGeneration++;
        } else if (updatesSelectedFilter) {
            Set<String> optimisticFilterIds = new java.util.HashSet<>(filteredConversationIds);
            if (assign) optimisticFilterIds.add(conversationId);
            else optimisticFilterIds.remove(conversationId);
            filteredConversationIds = Set.copyOf(optimisticFilterIds);
        }
        long mutationFilterGeneration = filterGeneration;
        long requestSessionGeneration = sessionGeneration;
        assignmentGeneration++;
        assignmentMutationConversationId = conversationId;
        assignmentMutationIds = List.copyOf(updated);
        assignmentIds = List.copyOf(updated);
        assignmentSaving = true;
        assignmentMutationInFlight = true;
        clearError();
        publish();

        Completable request = repository.cacheLabelIds(conversationId, updated)
                .andThen(assign
                        ? repository.assignLabel(conversationId, labelId)
                        : repository.unassignLabel(conversationId, labelId))
                .andThen(repository.cacheLabelIds(conversationId, updated).onErrorComplete())
                .onErrorResumeNext(failure -> repository.cacheLabelIds(conversationId, previous)
                        .onErrorComplete()
                        .andThen(Completable.error(failure)));
        disposables.add(request.subscribeOn(Schedulers.io()).subscribe(
                () -> {
                    synchronized (LabelViewModel.this) {
                        if (requestSessionGeneration != sessionGeneration) return;
                        assignmentMutationInFlight = false;
                        List<String> confirmedAssignmentIds = assignmentMutationIds;
                        assignmentMutationConversationId = null;
                        assignmentMutationIds = List.of();
                        finishAssignmentFilterUpdateLocked(
                                labelId, updatesSelectedFilter, filterWasLoading,
                                mutationFilterGeneration, previousFilteredIds, true);
                        if (!conversationId.equals(assignmentConversationId)) {
                            assignmentSaving = false;
                            publish();
                            return;
                        }
                        assignmentIds = confirmedAssignmentIds;
                        assignmentSaving = false;
                        clearError();
                        publish();
                    }
                },
                failure -> {
                    synchronized (LabelViewModel.this) {
                        if (requestSessionGeneration != sessionGeneration) return;
                        assignmentMutationInFlight = false;
                        assignmentMutationConversationId = null;
                        assignmentMutationIds = List.of();
                        finishAssignmentFilterUpdateLocked(
                                labelId, updatesSelectedFilter, filterWasLoading,
                                mutationFilterGeneration, previousFilteredIds, false);
                        if (!conversationId.equals(assignmentConversationId)) {
                            assignmentSaving = false;
                            publish();
                            return;
                        }
                        assignmentIds = previous;
                        assignmentSaving = false;
                        setError(errorFor(failure));
                    }
                }));
    }

    public synchronized void createLabel(@Nullable String name, @NonNull LabelColor color) {
        if (labelsMutating || assignmentMutationInFlight || !labelsLoaded || labelsLoading) return;
        if (labels.size() >= LabelRules.MAX_LABELS) {
            setError(LabelUiState.Error.LABEL_LIMIT);
            return;
        }
        String normalized = LabelRules.normalizeName(name);
        if (!LabelRules.isValidName(normalized)) {
            setError(LabelUiState.Error.VALIDATION);
            return;
        }
        if (LabelRules.hasDuplicateName(normalized, labels, null)) {
            setError(LabelUiState.Error.CONFLICT);
            return;
        }

        List<ConversationLabel> previous = labels;
        long requestSessionGeneration = sessionGeneration;
        String pendingId = "pending:" + UUID.randomUUID();
        List<ConversationLabel> optimistic = new ArrayList<>(labels);
        optimistic.add(new ConversationLabel(pendingId, normalized, color, true));
        labels = sorted(optimistic);
        labelsMutating = true;
        clearError();
        publish();
        disposables.add(repository.createLabel(normalized, color)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        saved -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                List<ConversationLabel> confirmed = new ArrayList<>(labels);
                                confirmed.removeIf(item -> pendingId.equals(item.getId()));
                                confirmed.add(saved);
                                labels = sorted(confirmed);
                                labelsMutating = false;
                                clearError();
                                publish();
                            }
                        },
                        failure -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                labels = previous;
                                labelsMutating = false;
                                setError(errorFor(failure));
                            }
                        }));
    }

    public synchronized void updateLabel(
            @NonNull String labelId, @Nullable String name, @NonNull LabelColor color) {
        if (labelsMutating || assignmentMutationInFlight) return;
        String normalized = LabelRules.normalizeName(name);
        if (!LabelRules.isValidName(normalized)) {
            setError(LabelUiState.Error.VALIDATION);
            return;
        }
        if (LabelRules.hasDuplicateName(normalized, labels, labelId)) {
            setError(LabelUiState.Error.CONFLICT);
            return;
        }
        ConversationLabel current = findLabel(labelId);
        if (current == null || current.isPending()) return;

        List<ConversationLabel> previous = labels;
        long requestSessionGeneration = sessionGeneration;
        labels = replaceLabel(labels, current.withNameAndColor(normalized, color));
        labelsMutating = true;
        clearError();
        publish();
        disposables.add(repository.updateLabel(labelId, normalized, color)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        saved -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                labels = replaceLabel(labels, saved);
                                labelsMutating = false;
                                clearError();
                                publish();
                            }
                        },
                        failure -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                labels = previous;
                                labelsMutating = false;
                                setError(errorFor(failure));
                            }
                        }));
    }

    public synchronized void deleteLabel(@NonNull String labelId) {
        if (labelsMutating || assignmentMutationInFlight) return;
        ConversationLabel current = findLabel(labelId);
        if (current == null || current.isPending()) return;

        List<ConversationLabel> previousLabels = labels;
        String previousFilter = selectedFilterId;
        Set<String> previousFilterIds = filteredConversationIds;
        List<String> previousAssignmentIds = assignmentIds;
        long requestSessionGeneration = sessionGeneration;
        labels = labels.stream().filter(item -> !labelId.equals(item.getId())).toList();
        if (labelId.equals(selectedFilterId)) {
            filterGeneration++;
            selectedFilterId = null;
            savedStateHandle.set(KEY_SELECTED_FILTER, null);
            filteredConversationIds = Set.of();
            filterLoading = false;
            filterError = LabelUiState.Error.NONE;
        }
        assignmentIds = assignmentIds.stream().filter(id -> !labelId.equals(id)).toList();
        labelsMutating = true;
        clearError();
        publish();
        disposables.add(repository.deleteLabel(labelId)
                .andThen(repository.removeLabelFromCache(labelId).onErrorComplete())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        () -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                labelsMutating = false;
                                clearError();
                                publish();
                            }
                        },
                        failure -> {
                            dispatchToOwnerThread(() -> {
                                synchronized (LabelViewModel.this) {
                                    if (requestSessionGeneration != sessionGeneration) return;
                                    labels = previousLabels;
                                    selectedFilterId = previousFilter;
                                    savedStateHandle.set(KEY_SELECTED_FILTER, previousFilter);
                                    filteredConversationIds = previousFilterIds;
                                    assignmentIds = previousAssignmentIds;
                                    labelsMutating = false;
                                    setError(errorFor(failure));
                                    if (previousFilter != null) loadFilterLocked(previousFilter);
                                }
                            });
                        }));
    }

    private void loadLabelsLocked() {
        long requestSessionGeneration = sessionGeneration;
        labelsLoading = true;
        clearError();
        publish();
        disposables.add(repository.listLabels()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            dispatchToOwnerThread(() -> {
                                synchronized (LabelViewModel.this) {
                                    if (requestSessionGeneration != sessionGeneration) return;
                                    labels = sorted(result);
                                    labelsLoading = false;
                                    labelsLoaded = true;
                                    assignmentIds = knownAssignments(assignmentIds);
                                    if (selectedFilterId != null
                                            && labels.stream().noneMatch(
                                                    label -> selectedFilterId.equals(label.getId()))) {
                                        selectedFilterId = null;
                                        savedStateHandle.set(KEY_SELECTED_FILTER, null);
                                        filteredConversationIds = Set.of();
                                        filterLoading = false;
                                        filterError = LabelUiState.Error.NONE;
                                        filterGeneration++;
                                    }
                                    clearError();
                                    publish();
                                }
                            });
                        },
                        failure -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration) return;
                                labelsLoading = false;
                                setError(errorFor(failure));
                            }
                        }));
    }

    private void loadFilterLocked(@NonNull String labelId) {
        long requestFilterGeneration = ++filterGeneration;
        long requestSessionGeneration = sessionGeneration;
        filterLoading = true;
        filterError = LabelUiState.Error.NONE;
        publish();
        disposables.add(repository.filterConversationIds(labelId)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        ids -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration
                                        || requestFilterGeneration != filterGeneration
                                        || !labelId.equals(selectedFilterId)) return;
                                filteredConversationIds = Set.copyOf(ids);
                                filterLoading = false;
                                filterError = LabelUiState.Error.NONE;
                                publish();
                            }
                        },
                        failure -> {
                            synchronized (LabelViewModel.this) {
                                if (requestSessionGeneration != sessionGeneration
                                        || requestFilterGeneration != filterGeneration
                                        || !labelId.equals(selectedFilterId)) return;
                                filteredConversationIds = Set.of();
                                filterLoading = false;
                                filterError = errorFor(failure);
                                publish();
                            }
                        }));
    }

    private void finishAssignmentFilterUpdateLocked(
            String labelId,
            boolean updatesSelectedFilter,
            boolean filterWasLoading,
            long mutationFilterGeneration,
            Set<String> previousFilteredIds,
            boolean succeeded) {
        if (!labelId.equals(selectedFilterId)) return;
        boolean refetch = filterWasLoading || !updatesSelectedFilter
                || filterGeneration != mutationFilterGeneration;
        if (refetch) {
            loadFilterLocked(labelId);
        } else if (!succeeded) {
            filteredConversationIds = previousFilteredIds;
        }
    }

    private synchronized void resetForLoggedOutSession() {
        disposables.clear();
        sessionGeneration++;
        filterGeneration++;
        assignmentGeneration++;
        labels = List.of();
        labelsLoading = false;
        labelsLoaded = false;
        selectedFilterId = null;
        savedStateHandle.set(KEY_SELECTED_FILTER, null);
        filteredConversationIds = Set.of();
        filterLoading = false;
        filterError = LabelUiState.Error.NONE;
        assignmentConversationId = null;
        assignmentIds = List.of();
        assignmentLoading = false;
        assignmentSaving = false;
        assignmentMutationInFlight = false;
        assignmentMutationConversationId = null;
        assignmentMutationIds = List.of();
        labelsMutating = false;
        error = LabelUiState.Error.NONE;
        errorVersion++;
        started = false;
        publish();
    }

    @Nullable private ConversationLabel findLabel(String labelId) {
        return labels.stream().filter(label -> labelId.equals(label.getId())).findFirst().orElse(null);
    }

    private List<String> knownAssignments(List<String> ids) {
        if (!labelsLoaded) return List.copyOf(ids);
        Set<String> known = labels.stream()
                .map(ConversationLabel::getId)
                .collect(java.util.stream.Collectors.toSet());
        return ids.stream().filter(known::contains).toList();
    }

    private void setError(LabelUiState.Error nextError) {
        error = nextError;
        errorVersion++;
        publish();
    }

    private void clearError() {
        error = LabelUiState.Error.NONE;
    }

    private void dispatchToOwnerThread(Runnable action) {
        if (Thread.currentThread() == ownerThread) {
            action.run();
            return;
        }
        Scheduler scheduler = mainThreadScheduler != null
                ? mainThreadScheduler : AndroidSchedulers.mainThread();
        mainThreadDispatches.add(scheduler.scheduleDirect(action));
    }

    private static LabelUiState.Error errorFor(Throwable failure) {
        if (failure instanceof LabelApiException api) {
            if (api.getStatus() == 400) return LabelUiState.Error.VALIDATION;
            if (api.getStatus() == 409) return LabelUiState.Error.CONFLICT;
            if (api.getStatus() == 429) return LabelUiState.Error.RATE_LIMITED;
        }
        if (failure instanceof IOException) return LabelUiState.Error.OFFLINE;
        return LabelUiState.Error.GENERIC;
    }

    private static List<ConversationLabel> replaceLabel(
            List<ConversationLabel> source, ConversationLabel replacement) {
        List<ConversationLabel> result = new ArrayList<>(source.size());
        boolean found = false;
        for (ConversationLabel label : source) {
            if (label.getId().equals(replacement.getId())) {
                result.add(replacement);
                found = true;
            } else {
                result.add(label);
            }
        }
        if (!found) result.add(replacement);
        return sorted(result);
    }

    private static List<ConversationLabel> sorted(List<ConversationLabel> source) {
        return source.stream()
                .sorted(Comparator.comparing(label -> label.getName().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    private synchronized void publish() {
        uiState.postValue(new LabelUiState(
                labels, labelsLoading, labelsLoaded, selectedFilterId,
                filteredConversationIds, filterLoading, filterError, assignmentConversationId,
                assignmentIds, assignmentLoading, assignmentSaving, labelsMutating,
                error, errorVersion));
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        mainThreadDispatches.dispose();
        if (sessionEventsSubscription != null) sessionEventsSubscription.dispose();
        super.onCleared();
    }
}
