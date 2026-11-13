package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Inject;

/** Owns the conversation reminder list, its editor transaction and push refreshes. */
@HiltViewModel
public final class ReminderViewModel extends ViewModel {

    public static final String ARG_CONVERSATION_ID = "conversationId";
    static final int MAX_TITLE_LENGTH = 200;

    private final String conversationId;
    private final ReminderRepository repository;
    private final Clock clock;
    private final MutableLiveData<ReminderUiState> uiState =
            new MutableLiveData<>(new ReminderUiState());
    private final ReminderEventLiveData events = new ReminderEventLiveData();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final List<Reminder> reminders = new ArrayList<>();

    private boolean loading;
    private boolean loaded;
    private boolean saving;
    @Nullable private ReminderRepository.Failure loadError;
    @Nullable private ReminderRepository.Failure refreshError;
    @Nullable private ReminderEditorError editorError;
    private long savedSequence;
    @Nullable private ReminderRepository.Cancellable activeLoad;
    @Nullable private CancellableTracker activeMutation;
    @Nullable private String pendingCreateKey;
    @Nullable private ReminderDraft pendingCreateDraft;
    private int generation;

    @Inject
    public ReminderViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull ReminderRepository repository,
            @NonNull ReminderPushEvents pushEvents) {
        this(savedState, repository, Clock.systemUTC(), pushEvents);
    }

    @VisibleForTesting
    ReminderViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull ReminderRepository repository,
            @NonNull Clock clock,
            @NonNull ReminderPushEvents pushEvents) {
        Objects.requireNonNull(savedState, "savedState");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(pushEvents, "pushEvents");
        String savedId = savedState.get(ARG_CONVERSATION_ID);
        conversationId = savedId == null ? "" : savedId;
        disposables.add(pushEvents.conversationIds()
                .filter(id -> id.equals(conversationId))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> refresh(), ignored -> { }));
        publish();
    }

    @NonNull
    public LiveData<ReminderUiState> getUiState() {
        return uiState;
    }

    @NonNull
    public LiveData<ReminderEventLiveData.Event> getEvents() {
        return events;
    }

    /** Initial load; shows the full-surface spinner. */
    public void load() {
        requestList(false);
    }

    /** Re-reads the list after a resume or a matching REMINDER push. */
    public void refresh() {
        requestList(true);
    }

    public void retry() {
        requestList(false);
    }

    /** Retries a failed silent refresh while keeping the cached reminder list visible. */
    public void retryRefresh() {
        if (loading) {
            return;
        }
        refreshError = null;
        publish();
        requestList(true);
    }

    public void openEditor() {
        editorError = null;
        publish();
    }

    public void clearEditorState() {
        if (saving) {
            return;
        }
        editorError = null;
        savedSequence = 0L;
        publish();
    }

    /**
     * Validates and persists a reminder. {@code reminderId == null} creates; any
     * other value edits an existing reminder the caller may edit.
     */
    public void saveReminder(
            @Nullable String reminderId,
            @NonNull String title,
            @Nullable Instant remindAt,
            @Nullable ReminderRepeat repeat) {
        if (saving) {
            return;
        }
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            failEditor(ReminderEditorError.TITLE_REQUIRED);
            return;
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            failEditor(ReminderEditorError.TITLE_TOO_LONG);
            return;
        }
        if (remindAt == null) {
            failEditor(ReminderEditorError.TIME_REQUIRED);
            return;
        }
        if (!remindAt.isAfter(clock.instant())) {
            failEditor(ReminderEditorError.TIME_NOT_FUTURE);
            return;
        }
        if (!ReminderTime.isFutureWithinYear(remindAt, clock)) {
            failEditor(ReminderEditorError.TIME_TOO_FAR);
            return;
        }
        ReminderRepeat effectiveRepeat = repeat == null ? ReminderRepeat.NONE : repeat;
        if (reminderId != null) {
            Reminder existing = findReminder(reminderId);
            if (existing == null) {
                failEditor(ReminderEditorError.NOT_FOUND);
                return;
            }
            if (!existing.isCanEdit()) {
                failEditor(ReminderEditorError.FORBIDDEN);
                return;
            }
        }

        ReminderDraft draft = new ReminderDraft(trimmed, remindAt, effectiveRepeat);
        saving = true;
        editorError = null;
        publish();
        activeMutation = new CancellableTracker();
        if (reminderId == null) {
            String key = idempotencyKeyFor(draft);
            activeMutation.cancellable = repository.create(
                    conversationId, draft, key, new ReminderRepository.ItemCallback() {
                        @Override
                        public void onSuccess(@NonNull Reminder reminder) {
                            onSaved(reminder);
                        }

                        @Override
                        public void onFailure(@NonNull ReminderRepository.Failure failure) {
                            onSaveFailed(failure, true);
                        }
                    });
        } else {
            activeMutation.cancellable = repository.update(
                    conversationId,
                    reminderId,
                    draft.getTitle(),
                    draft.getRemindAt(),
                    draft.getRepeat(),
                    new ReminderRepository.ItemCallback() {
                        @Override
                        public void onSuccess(@NonNull Reminder reminder) {
                            onSaved(reminder);
                        }

                        @Override
                        public void onFailure(@NonNull ReminderRepository.Failure failure) {
                            onSaveFailed(failure, false);
                        }
                    });
        }
    }

    public void deleteReminder(@NonNull String reminderId) {
        Reminder existing = findReminder(reminderId);
        if (existing == null) {
            events.emit(ReminderEditorError.NOT_FOUND);
            return;
        }
        if (!existing.isCanEdit()) {
            events.emit(ReminderEditorError.FORBIDDEN);
            return;
        }
        CancellableTracker tracker = new CancellableTracker();
        activeMutation = tracker;
        tracker.cancellable = repository.delete(
                conversationId, reminderId, new ReminderRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        reminders.removeIf(reminder -> reminder.getId().equals(reminderId));
                        loaded = true;
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull ReminderRepository.Failure failure) {
                        events.emit(ReminderEditorError.fromFailure(failure));
                    }
                });
    }

    private void onSaved(Reminder reminder) {
        saving = false;
        pendingCreateKey = null;
        pendingCreateDraft = null;
        replaceOrAdd(reminder);
        reminders.sort(Comparator.comparing(Reminder::getRemindAt));
        loaded = true;
        loadError = null;
        savedSequence++;
        activeMutation = null;
        publish();
    }

    private void onSaveFailed(@NonNull ReminderRepository.Failure failure, boolean creating) {
        saving = false;
        activeMutation = null;
        if (creating && failure == ReminderRepository.Failure.CONFLICT) {
            pendingCreateKey = null;
            pendingCreateDraft = null;
        }
        editorError = ReminderEditorError.fromFailure(failure);
        publish();
    }

    private void failEditor(ReminderEditorError error) {
        editorError = error;
        publish();
    }

    private void requestList(boolean silentFailure) {
        if (conversationId.isBlank()) {
            loaded = true;
            loading = false;
            loadError = ReminderRepository.Failure.UNKNOWN;
            publish();
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        if (!silentFailure) {
            loadError = null;
        }
        publish();
        int requestGeneration = ++generation;
        activeLoad = repository.load(conversationId, new ReminderRepository.ListCallback() {
            @Override
            public void onSuccess(@NonNull List<Reminder> loadedReminders) {
                if (requestGeneration != generation) {
                    return;
                }
                reminders.clear();
                reminders.addAll(loadedReminders);
                reminders.sort(Comparator.comparing(Reminder::getRemindAt));
                loading = false;
                loaded = true;
                loadError = null;
                refreshError = null;
                activeLoad = null;
                publish();
            }

            @Override
            public void onFailure(@NonNull ReminderRepository.Failure failure) {
                if (requestGeneration != generation) {
                    return;
                }
                loading = false;
                loaded = true;
                activeLoad = null;
                if (silentFailure) {
                    if (!reminders.isEmpty()) {
                        refreshError = failure;
                    }
                } else {
                    loadError = failure;
                }
                publish();
            }
        });
    }

    private void replaceOrAdd(Reminder reminder) {
        for (int index = 0; index < reminders.size(); index++) {
            if (reminders.get(index).getId().equals(reminder.getId())) {
                reminders.set(index, reminder);
                return;
            }
        }
        reminders.add(reminder);
    }

    @Nullable
    private Reminder findReminder(@NonNull String reminderId) {
        for (Reminder reminder : reminders) {
            if (reminder.getId().equals(reminderId)) {
                return reminder;
            }
        }
        return null;
    }

    /**
     * A failed create keeps its key while the exact payload is retried, so an
     * ambiguous transport failure cannot create a second reminder. Editing the
     * draft replaces the key because the server rejects key reuse with a
     * different payload.
     */
    @NonNull
    private String idempotencyKeyFor(@NonNull ReminderDraft draft) {
        if (pendingCreateKey != null && draft.equals(pendingCreateDraft)) {
            return pendingCreateKey;
        }
        pendingCreateKey = UUID.randomUUID().toString();
        pendingCreateDraft = draft;
        return pendingCreateKey;
    }

    private void publish() {
        uiState.setValue(new ReminderUiState(
                loading, loaded, Collections.unmodifiableList(new ArrayList<>(reminders)),
                loadError, refreshError, saving, editorError, savedSequence));
    }

    @Override
    protected void onCleared() {
        generation++;
        if (activeLoad != null) {
            activeLoad.cancel();
            activeLoad = null;
        }
        if (activeMutation != null && activeMutation.cancellable != null) {
            activeMutation.cancellable.cancel();
            activeMutation = null;
        }
        disposables.clear();
        super.onCleared();
    }

    private static final class CancellableTracker {
        @Nullable private ReminderRepository.Cancellable cancellable;
    }
}
