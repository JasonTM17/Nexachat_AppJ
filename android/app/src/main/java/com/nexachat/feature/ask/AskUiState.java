package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Immutable, in-memory presentation state for one ask sheet. */
public final class AskUiState {
    public enum Status {
        IDLE,
        LOADING,
        ANSWER,
        NOT_FOUND,
        ERROR,
        RATE_LIMITED,
        CONSENT_REQUIRED
    }

    private final Status status;
    private final String draft;
    @Nullable private final AskResponse response;
    @Nullable private final AskError error;
    @Nullable private final Long retryAfterSeconds;

    private AskUiState(
            Status status,
            String draft,
            @Nullable AskResponse response,
            @Nullable AskError error,
            @Nullable Long retryAfterSeconds) {
        this.status = Objects.requireNonNull(status, "status");
        this.draft = Objects.requireNonNull(draft, "draft");
        this.response = response;
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static AskUiState idle(String draft) {
        return new AskUiState(Status.IDLE, draft, null, null, null);
    }

    public static AskUiState loading(String draft) {
        return new AskUiState(Status.LOADING, draft, null, null, null);
    }

    public static AskUiState answer(String draft, AskResponse response) {
        return new AskUiState(Status.ANSWER, draft, response, null, null);
    }

    public static AskUiState notFound(String draft) {
        return new AskUiState(Status.NOT_FOUND, draft, null, null, null);
    }

    public static AskUiState error(String draft, AskError error) {
        return new AskUiState(Status.ERROR, draft, null, error, null);
    }

    public static AskUiState rateLimited(String draft, @Nullable Long retryAfterSeconds) {
        return new AskUiState(Status.RATE_LIMITED, draft, null,
                AskError.RATE_LIMITED, retryAfterSeconds);
    }

    public static AskUiState consentRequired(String draft) {
        return new AskUiState(Status.CONSENT_REQUIRED, draft, null,
                AskError.CONSENT_REQUIRED, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getDraft() {
        return draft;
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
}
