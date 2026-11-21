package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** One completed question in the in-memory ask sheet thread. */
public final class AskTurn {
    public enum Status {
        ANSWER,
        NOT_FOUND,
        ERROR,
        RATE_LIMITED,
        CONSENT_REQUIRED
    }

    private final String question;
    private final Status status;
    @Nullable private final AskResponse response;
    @Nullable private final AskError error;
    @Nullable private final Long retryAfterSeconds;
    private final boolean contextOmitted;

    private AskTurn(
            String question,
            Status status,
            @Nullable AskResponse response,
            @Nullable AskError error,
            @Nullable Long retryAfterSeconds,
            boolean contextOmitted) {
        this.question = Objects.requireNonNull(question, "question");
        this.status = Objects.requireNonNull(status, "status");
        this.response = response;
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
        this.contextOmitted = contextOmitted;
    }

    public static AskTurn answer(@NonNull String question, @NonNull AskResponse response) {
        return new AskTurn(question, Status.ANSWER, response, null, null, response.isContextOmitted());
    }

    public static AskTurn notFound(@NonNull String question, boolean contextOmitted) {
        return new AskTurn(question, Status.NOT_FOUND, null, null, null, contextOmitted);
    }

    public static AskTurn error(@NonNull String question, @NonNull AskError error) {
        return new AskTurn(question, Status.ERROR, null, error, null, false);
    }

    public static AskTurn rateLimited(@NonNull String question, @Nullable Long retryAfterSeconds) {
        return new AskTurn(question, Status.RATE_LIMITED, null, AskError.RATE_LIMITED,
                retryAfterSeconds, false);
    }

    public static AskTurn consentRequired(@NonNull String question) {
        return new AskTurn(question, Status.CONSENT_REQUIRED, null, AskError.CONSENT_REQUIRED,
                null, false);
    }

    @NonNull
    public String getQuestion() {
        return question;
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    @Nullable
    public AskResponse getResponse() {
        return response;
    }

    @Nullable
    public AskError getError() {
        return error;
    }

    @Nullable
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isContextOmitted() {
        return contextOmitted;
    }
}
