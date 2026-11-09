package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** Cancellable poll data boundary used by the board and its unit tests. */
public interface PollRepository {

    Cancellable load(
            @NonNull String conversationId,
            @Nullable String before,
            @NonNull PageCallback callback);

    Cancellable changes(
            @NonNull String conversationId,
            long since,
            int limit,
            @NonNull ChangesCallback callback);

    Cancellable create(
            @NonNull String conversationId,
            @NonNull PollDraft draft,
            @NonNull ItemCallback callback);

    Cancellable vote(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull List<String> optionIds,
            @NonNull ItemCallback callback);

    Cancellable retractVote(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull ItemCallback callback);

    Cancellable close(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull ItemCallback callback);

    Cancellable delete(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull DeleteCallback callback);

    enum Failure {
        OFFLINE,
        NOT_MEMBER,
        NOT_ADMIN,
        NOT_A_GROUP,
        NOT_FOUND,
        CLOSED,
        VALIDATION,
        RATE_LIMITED,
        UNKNOWN
    }

    interface Cancellable {
        void cancel();
    }

    interface PageCallback {
        void onSuccess(@NonNull PollPage page);

        void onFailure(@NonNull Failure failure);
    }

    interface ChangesCallback {
        void onSuccess(@NonNull PollChanges changes);

        void onFailure(@NonNull Failure failure);
    }

    interface ItemCallback {
        void onSuccess(@NonNull Poll poll);

        void onFailure(@NonNull Failure failure);
    }

    interface DeleteCallback {
        void onSuccess();

        void onFailure(@NonNull Failure failure);
    }
}
