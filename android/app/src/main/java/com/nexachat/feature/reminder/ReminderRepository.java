package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/** Cancellable reminder data boundary used by the screen and its unit tests. */
public interface ReminderRepository {

    Cancellable load(@NonNull String conversationId, @NonNull ListCallback callback);

    Cancellable create(
            @NonNull String conversationId,
            @NonNull ReminderDraft draft,
            @NonNull String idempotencyKey,
            @NonNull ItemCallback callback);

    Cancellable update(
            @NonNull String conversationId,
            @NonNull String reminderId,
            @Nullable String title,
            @Nullable Instant remindAt,
            @Nullable ReminderRepeat repeat,
            @NonNull ItemCallback callback);

    Cancellable delete(
            @NonNull String conversationId,
            @NonNull String reminderId,
            @NonNull DeleteCallback callback);

    /** User-visible failure classes; server messages are never surfaced. */
    enum Failure {
        OFFLINE,
        NOT_MEMBER,
        NOT_ADMIN,
        FORBIDDEN,
        NOT_FOUND,
        VALIDATION,
        LIMIT,
        RATE_LIMITED,
        CONFLICT,
        UNKNOWN
    }

    interface Cancellable {
        void cancel();
    }

    interface ListCallback {
        void onSuccess(@NonNull List<Reminder> reminders);

        void onFailure(@NonNull Failure failure);
    }

    interface ItemCallback {
        void onSuccess(@NonNull Reminder reminder);

        void onFailure(@NonNull Failure failure);
    }

    interface DeleteCallback {
        void onSuccess();

        void onFailure(@NonNull Failure failure);
    }
}
