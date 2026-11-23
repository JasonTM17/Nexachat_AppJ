package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import java.util.List;

/** Cancellable scheduled-message data boundary used by the sheets and their unit tests. */
public interface ScheduledMessageRepository {

    Cancellable load(@NonNull String conversationId, @NonNull ListCallback callback);

    Cancellable create(
            @NonNull String conversationId,
            @NonNull ScheduledMessageDraft draft,
            @NonNull String clientMessageId,
            @NonNull ItemCallback callback);

    Cancellable delete(
            @NonNull String conversationId,
            @NonNull String scheduledId,
            @NonNull DeleteCallback callback);

    /** User-visible failure classes; server messages are never surfaced. */
    enum Failure {
        OFFLINE,
        NOT_MEMBER,
        FORBIDDEN,
        NOT_FOUND,
        VALIDATION,
        LIMIT,
        RATE_LIMITED,
        CONFLICT,
        DISPATCHING,
        E2EE,
        UNKNOWN
    }

    interface Cancellable {
        void cancel();
    }

    interface ListCallback {
        void onSuccess(@NonNull List<ScheduledMessage> messages);

        void onFailure(@NonNull Failure failure);
    }

    interface ItemCallback {
        void onSuccess(@NonNull ScheduledMessage message);

        void onFailure(@NonNull Failure failure);
    }

    interface DeleteCallback {
        void onSuccess();

        void onFailure(@NonNull Failure failure);
    }
}
