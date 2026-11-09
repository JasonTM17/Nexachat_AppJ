package com.nexachat.feature.reminder;

import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** Reflective Moshi bean for one reminder response. */
public final class ReminderDto {
    @Nullable private final String id;
    @Nullable private final String conversationId;
    @Nullable private final String title;
    @Nullable private final String remindAt;
    @Nullable private final String repeat;
    @Nullable private final String createdBy;
    @Nullable private final String createdByName;
    @Nullable private final Boolean canEdit;
    @Nullable private final String createdAt;
    @Nullable private final String updatedAt;

    public ReminderDto() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    public ReminderDto(
            @Nullable String id,
            @Nullable String conversationId,
            @Nullable String title,
            @Nullable String remindAt,
            @Nullable String repeat,
            @Nullable String createdBy,
            @Nullable String createdByName,
            @Nullable Boolean canEdit,
            @Nullable String createdAt,
            @Nullable String updatedAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.title = title;
        this.remindAt = remindAt;
        this.repeat = repeat;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.canEdit = canEdit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @Nullable public String getId() { return id; }
    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public String getTitle() { return title; }
    @Nullable public String getRemindAt() { return remindAt; }
    @Nullable public String getRepeat() { return repeat; }
    @Nullable public String getCreatedBy() { return createdBy; }
    @Nullable public String getCreatedByName() { return createdByName; }
    public boolean isCanEdit() { return Boolean.TRUE.equals(canEdit); }
    @Nullable public String getCreatedAt() { return createdAt; }
    @Nullable public String getUpdatedAt() { return updatedAt; }

    /** Returns null when the server row is not a renderable reminder. */
    @Nullable
    public Reminder toDomain() {
        if (isBlank(id) || isBlank(conversationId) || isBlank(title)) {
            return null;
        }
        Instant remind = ReminderTime.parse(remindAt);
        if (remind == null) {
            return null;
        }
        return new Reminder(
                Objects.requireNonNull(id),
                Objects.requireNonNull(conversationId),
                Objects.requireNonNull(title),
                remind,
                createdBy,
                createdByName,
                ReminderRepeat.fromWire(repeat),
                isCanEdit(),
                ReminderTime.parse(createdAt),
                ReminderTime.parse(updatedAt));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
