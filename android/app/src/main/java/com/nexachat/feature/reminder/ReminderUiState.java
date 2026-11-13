package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** Immutable render state for the reminder tab. */
public final class ReminderUiState {

    private final boolean loading;
    private final boolean loaded;
    private final List<Reminder> reminders;
    @Nullable private final ReminderRepository.Failure loadError;
    @Nullable private final ReminderRepository.Failure refreshError;
    private final boolean saving;
    @Nullable private final ReminderEditorError editorError;
    private final long savedSequence;

    public ReminderUiState() {
        this(false, false, Collections.emptyList(), null, null, false, null, 0L);
    }

    public ReminderUiState(
            boolean loading,
            boolean loaded,
            @NonNull List<Reminder> reminders,
            @Nullable ReminderRepository.Failure loadError,
            @Nullable ReminderRepository.Failure refreshError,
            boolean saving,
            @Nullable ReminderEditorError editorError,
            long savedSequence) {
        this.loading = loading;
        this.loaded = loaded;
        this.reminders = List.copyOf(reminders);
        this.loadError = loadError;
        this.refreshError = refreshError;
        this.saving = saving;
        this.editorError = editorError;
        this.savedSequence = savedSequence;
    }

    public boolean isLoading() { return loading; }
    public boolean isLoaded() { return loaded; }
    @NonNull public List<Reminder> getReminders() { return reminders; }
    @Nullable public ReminderRepository.Failure getLoadError() { return loadError; }
    @Nullable public ReminderRepository.Failure getRefreshError() { return refreshError; }
    public boolean isSaving() { return saving; }
    @Nullable public ReminderEditorError getEditorError() { return editorError; }
    public long getSavedSequence() { return savedSequence; }

    public boolean isEmpty() {
        return loaded && !loading && loadError == null && reminders.isEmpty();
    }

    @Nullable
    public Reminder find(@Nullable String reminderId) {
        if (reminderId == null) {
            return null;
        }
        for (Reminder reminder : reminders) {
            if (reminder.getId().equals(reminderId)) {
                return reminder;
            }
        }
        return null;
    }
}
