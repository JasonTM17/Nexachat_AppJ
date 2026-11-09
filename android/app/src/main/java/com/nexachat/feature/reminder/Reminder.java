package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** One upcoming reminder of a conversation, as rendered by the reminder tab. */
public final class Reminder {

    private final String id;
    private final String conversationId;
    private final String title;
    private final Instant remindAt;
    @Nullable private final String createdBy;
    @Nullable private final String createdByName;
    private final ReminderRepeat repeat;
    private final boolean canEdit;
    @Nullable private final Instant createdAt;
    @Nullable private final Instant updatedAt;

    public Reminder(
            @NonNull String id,
            @NonNull String conversationId,
            @NonNull String title,
            @NonNull Instant remindAt,
            @Nullable String createdBy,
            @Nullable String createdByName,
            @NonNull ReminderRepeat repeat,
            boolean canEdit,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.title = Objects.requireNonNull(title, "title");
        this.remindAt = Objects.requireNonNull(remindAt, "remindAt");
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.repeat = Objects.requireNonNull(repeat, "repeat");
        this.canEdit = canEdit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getTitle() { return title; }
    @NonNull public Instant getRemindAt() { return remindAt; }
    @Nullable public String getCreatedBy() { return createdBy; }
    @Nullable public String getCreatedByName() { return createdByName; }
    @NonNull public ReminderRepeat getRepeat() { return repeat; }
    public boolean isCanEdit() { return canEdit; }
    @Nullable public Instant getCreatedAt() { return createdAt; }
    @Nullable public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Reminder that)) {
            return false;
        }
        return canEdit == that.canEdit
                && id.equals(that.id)
                && conversationId.equals(that.conversationId)
                && title.equals(that.title)
                && remindAt.equals(that.remindAt)
                && Objects.equals(createdBy, that.createdBy)
                && Objects.equals(createdByName, that.createdByName)
                && repeat == that.repeat
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, conversationId, title, remindAt, createdBy, createdByName,
                repeat, canEdit, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "Reminder(id=" + id + ", title=" + title + ", remindAt=" + remindAt
                + ", repeat=" + repeat + ", canEdit=" + canEdit + ")";
    }
}
