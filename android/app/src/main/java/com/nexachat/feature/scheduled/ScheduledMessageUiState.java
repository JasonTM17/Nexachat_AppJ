package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** Immutable render state for the scheduled-messages sheet. */
public final class ScheduledMessageUiState {

    private final boolean loading;
    private final boolean loaded;
    private final List<ScheduledMessage> messages;
    @Nullable private final ScheduledMessageRepository.Failure loadError;
    @Nullable private final ScheduledMessageRepository.Failure refreshError;
    private final boolean scheduling;
    @Nullable private final ScheduledMessageEditorError editorError;
    private final long savedSequence;
    /** The row echoed by the last successful schedule; carries the clamped sendAt. */
    @Nullable private final ScheduledMessage scheduledMessage;
    /** True when {@link #savedSequence} last advanced because of an edit, not a create. */
    private final boolean lastSaveWasEdit;

    public ScheduledMessageUiState() {
        this(false, false, Collections.emptyList(), null, null, false, null, 0L, null, false);
    }

    public ScheduledMessageUiState(
            boolean loading,
            boolean loaded,
            @NonNull List<ScheduledMessage> messages,
            @Nullable ScheduledMessageRepository.Failure loadError,
            @Nullable ScheduledMessageRepository.Failure refreshError,
            boolean scheduling,
            @Nullable ScheduledMessageEditorError editorError,
            long savedSequence,
            @Nullable ScheduledMessage scheduledMessage,
            boolean lastSaveWasEdit) {
        this.loading = loading;
        this.loaded = loaded;
        this.messages = List.copyOf(messages);
        this.loadError = loadError;
        this.refreshError = refreshError;
        this.scheduling = scheduling;
        this.editorError = editorError;
        this.savedSequence = savedSequence;
        this.scheduledMessage = scheduledMessage;
        this.lastSaveWasEdit = lastSaveWasEdit;
    }

    public boolean isLoading() { return loading; }
    public boolean isLoaded() { return loaded; }
    @NonNull public List<ScheduledMessage> getMessages() { return messages; }
    @Nullable public ScheduledMessageRepository.Failure getLoadError() { return loadError; }
    @Nullable public ScheduledMessageRepository.Failure getRefreshError() { return refreshError; }
    public boolean isScheduling() { return scheduling; }
    @Nullable public ScheduledMessageEditorError getEditorError() { return editorError; }
    public long getSavedSequence() { return savedSequence; }
    @Nullable public ScheduledMessage getScheduledMessage() { return scheduledMessage; }
    public boolean isLastSaveWasEdit() { return lastSaveWasEdit; }

    public boolean isEmpty() {
        return loaded && !loading && loadError == null && messages.isEmpty();
    }
}
