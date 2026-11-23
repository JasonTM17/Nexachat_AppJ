package com.nexachat.feature.scheduled;

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

/**
 * Owns the caller's scheduled rows for the open conversation, the schedule-sheet
 * transaction, and realtime refreshes. Scoped to the chat fragment so the schedule
 * sheet and the list sheet share one store.
 */
@HiltViewModel
public final class ScheduledMessagesViewModel extends ViewModel {

    public static final String ARG_CONVERSATION_ID = "conversationId";
    static final int MAX_BODY_LENGTH = 10_000;

    private final String conversationId;
    private final ScheduledMessageRepository repository;
    private final Clock clock;
    private final MutableLiveData<ScheduledMessageUiState> uiState =
            new MutableLiveData<>(new ScheduledMessageUiState());
    private final ScheduledMessageEventLiveData events = new ScheduledMessageEventLiveData();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final List<ScheduledMessage> messages = new ArrayList<>();

    private boolean loading;
    private boolean loaded;
    private boolean scheduling;
    @Nullable private ScheduledMessageRepository.Failure loadError;
    @Nullable private ScheduledMessageRepository.Failure refreshError;
    @Nullable private ScheduledMessageEditorError editorError;
    private long savedSequence;
    @Nullable private ScheduledMessage scheduledMessage;
    private boolean lastSaveWasEdit;
    @Nullable private ScheduledMessageRepository.Cancellable activeLoad;
    @Nullable private CancellableTracker activeMutation;
    @Nullable private String pendingCreateId;
    @Nullable private ScheduledMessageDraft pendingCreateDraft;
    private int generation;

    @Inject
    public ScheduledMessagesViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull ScheduledMessageRepository repository,
            @NonNull ScheduledMessageEventSource eventSource) {
        this(savedState, repository, Clock.systemUTC(), eventSource);
    }

    @VisibleForTesting
    ScheduledMessagesViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull ScheduledMessageRepository repository,
            @NonNull Clock clock,
            @NonNull ScheduledMessageEventSource eventSource) {
        Objects.requireNonNull(savedState, "savedState");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(eventSource, "eventSource");
        String savedId = savedState.get(ARG_CONVERSATION_ID);
        conversationId = savedId == null ? "" : savedId;
        disposables.add(eventSource.updates()
                .filter(update -> conversationId.equals(update.getConversationId()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> refresh(), ignored -> { }));
        publish();
    }

    @NonNull
    public LiveData<ScheduledMessageUiState> getUiState() {
        return uiState;
    }

    @NonNull
    public LiveData<ScheduledMessageEventLiveData.Event> getEvents() {
        return events;
    }

    /** Initial load; shows the sheet's spinner. */
    public void load() {
        requestList(false);
    }

    /** Re-reads the list after a sheet reopen or a SCHEDULED_MESSAGE_UPDATED push. */
    public void refresh() {
        requestList(true);
    }

    public void retry() {
        requestList(false);
    }

    /** Retries a failed silent refresh while keeping the cached list visible. */
    public void retryRefresh() {
        if (loading) {
            return;
        }
        refreshError = null;
        publish();
        requestList(true);
    }

    /** Resets sheet-local state before each schedule sheet opens. */
    public void openScheduler() {
        editorError = null;
        scheduledMessage = null;
        publish();
    }

    /**
     * Validates and schedules a text draft. The echoed row carries the server's
     * clamped {@code sendAt}, which is what the UI confirms back to the user.
     */
    public void schedule(@Nullable String body, @Nullable Instant sendAt) {
        if (scheduling) {
            return;
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            failEditor(ScheduledMessageEditorError.BODY_REQUIRED);
            return;
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            failEditor(ScheduledMessageEditorError.BODY_TOO_LONG);
            return;
        }
        if (sendAt == null) {
            failEditor(ScheduledMessageEditorError.TIME_REQUIRED);
            return;
        }
        if (!ScheduledMessageTime.isWithinLeadTime(sendAt, clock)) {
            failEditor(ScheduledMessageEditorError.TIME_TOO_SOON);
            return;
        }

        ScheduledMessageDraft draft = new ScheduledMessageDraft(trimmed, sendAt);
        scheduling = true;
        editorError = null;
        scheduledMessage = null;
        publish();
        activeMutation = new CancellableTracker();
        String clientMessageId = clientMessageIdFor(draft);
        activeMutation.cancellable = repository.create(
                conversationId, draft, clientMessageId,
                new ScheduledMessageRepository.ItemCallback() {
                    @Override
                    public void onSuccess(@NonNull ScheduledMessage message) {
                        onScheduled(message, false);
                    }

                    @Override
                    public void onFailure(@NonNull ScheduledMessageRepository.Failure failure) {
                        onScheduleFailed(failure);
                    }
                });
    }

    /**
     * Validates and edits a still-PENDING row in place. Unlike {@link #schedule}
     * there is no idempotency handle: a PATCH replay targets the same row and is
     * naturally idempotent, and a claimed/terminal answer re-syncs the list.
     */
    public void edit(
            @NonNull String scheduledId,
            @Nullable String body,
            @Nullable Instant sendAt) {
        if (scheduling) {
            return;
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            failEditor(ScheduledMessageEditorError.BODY_REQUIRED);
            return;
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            failEditor(ScheduledMessageEditorError.BODY_TOO_LONG);
            return;
        }
        if (sendAt == null) {
            failEditor(ScheduledMessageEditorError.TIME_REQUIRED);
            return;
        }
        if (!ScheduledMessageTime.isWithinLeadTime(sendAt, clock)) {
            failEditor(ScheduledMessageEditorError.TIME_TOO_SOON);
            return;
        }

        ScheduledMessageDraft draft = new ScheduledMessageDraft(trimmed, sendAt);
        scheduling = true;
        editorError = null;
        scheduledMessage = null;
        publish();
        activeMutation = new CancellableTracker();
        activeMutation.cancellable = repository.update(
                conversationId, scheduledId, draft,
                new ScheduledMessageRepository.ItemCallback() {
                    @Override
                    public void onSuccess(@NonNull ScheduledMessage message) {
                        onScheduled(message, true);
                    }

                    @Override
                    public void onFailure(@NonNull ScheduledMessageRepository.Failure failure) {
                        onScheduleFailed(failure);
                        if (failure == ScheduledMessageRepository.Failure.DISPATCHING
                                || failure == ScheduledMessageRepository.Failure.NOT_FOUND) {
                            refresh();
                        }
                    }
                });
    }

    /** Cancels a still-PENDING row; DISPATCHING rows re-sync the list instead. */
    public void cancelScheduled(@NonNull String scheduledId) {
        ScheduledMessage existing = findMessage(scheduledId);
        if (existing == null) {
            events.emit(ScheduledMessageEditorError.NOT_FOUND);
            return;
        }
        if (!existing.isCancelable()) {
            events.emit(ScheduledMessageEditorError.DISPATCHING);
            refresh();
            return;
        }
        CancellableTracker tracker = new CancellableTracker();
        activeMutation = tracker;
        tracker.cancellable = repository.delete(
                conversationId, scheduledId,
                new ScheduledMessageRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        messages.removeIf(message -> message.getId().equals(scheduledId));
                        loaded = true;
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull ScheduledMessageRepository.Failure failure) {
                        events.emit(ScheduledMessageEditorError.fromFailure(failure));
                        if (failure == ScheduledMessageRepository.Failure.DISPATCHING
                                || failure == ScheduledMessageRepository.Failure.NOT_FOUND) {
                            refresh();
                        }
                    }
                });
    }

    private void onScheduled(ScheduledMessage message, boolean wasEdit) {
        scheduling = false;
        pendingCreateId = null;
        pendingCreateDraft = null;
        replaceOrAdd(message);
        messages.sort(Comparator.comparing(ScheduledMessage::getSendAt));
        loaded = true;
        loadError = null;
        savedSequence++;
        scheduledMessage = message;
        lastSaveWasEdit = wasEdit;
        activeMutation = null;
        publish();
    }

    private void onScheduleFailed(@NonNull ScheduledMessageRepository.Failure failure) {
        scheduling = false;
        activeMutation = null;
        if (failure == ScheduledMessageRepository.Failure.CONFLICT) {
            // The id can no longer resolve to a live row; the next attempt mints a new one.
            pendingCreateId = null;
            pendingCreateDraft = null;
        }
        editorError = ScheduledMessageEditorError.fromFailure(failure);
        publish();
    }

    private void failEditor(ScheduledMessageEditorError error) {
        editorError = error;
        publish();
    }

    private void requestList(boolean silentFailure) {
        if (conversationId.isBlank()) {
            loaded = true;
            loading = false;
            loadError = ScheduledMessageRepository.Failure.UNKNOWN;
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
        activeLoad = repository.load(conversationId,
                new ScheduledMessageRepository.ListCallback() {
                    @Override
                    public void onSuccess(@NonNull List<ScheduledMessage> loadedMessages) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        messages.clear();
                        messages.addAll(loadedMessages);
                        messages.sort(Comparator.comparing(ScheduledMessage::getSendAt));
                        loading = false;
                        loaded = true;
                        loadError = null;
                        refreshError = null;
                        activeLoad = null;
                        publish();
                    }

                    @Override
                    public void onFailure(@NonNull ScheduledMessageRepository.Failure failure) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        loading = false;
                        loaded = true;
                        activeLoad = null;
                        if (silentFailure) {
                            if (!messages.isEmpty()) {
                                refreshError = failure;
                            }
                        } else {
                            loadError = failure;
                        }
                        publish();
                    }
                });
    }

    private void replaceOrAdd(ScheduledMessage message) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).getId().equals(message.getId())) {
                messages.set(index, message);
                return;
            }
        }
        messages.add(message);
    }

    @Nullable
    private ScheduledMessage findMessage(@NonNull String scheduledId) {
        for (ScheduledMessage message : messages) {
            if (message.getId().equals(scheduledId)) {
                return message;
            }
        }
        return null;
    }

    /**
     * A failed schedule keeps its clientMessageId while the exact payload is
     * retried, so an ambiguous transport failure replays the same row on the
     * server instead of writing a second one. Editing the draft mints a new id.
     */
    @NonNull
    private String clientMessageIdFor(@NonNull ScheduledMessageDraft draft) {
        if (pendingCreateId != null && draft.equals(pendingCreateDraft)) {
            return pendingCreateId;
        }
        pendingCreateId = UUID.randomUUID().toString();
        pendingCreateDraft = draft;
        return pendingCreateId;
    }

    private void publish() {
        uiState.setValue(new ScheduledMessageUiState(
                loading, loaded, Collections.unmodifiableList(new ArrayList<>(messages)),
                loadError, refreshError, scheduling, editorError, savedSequence,
                scheduledMessage, lastSaveWasEdit));
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
        @Nullable private ScheduledMessageRepository.Cancellable cancellable;
    }
}
