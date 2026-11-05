package com.nexachat.summary;

/** Immutable state exposed by {@link SummaryViewModel}. */
public final class SummaryUiState {
    public enum Status {
        IDLE,
        LOADING,
        READY,
        ERROR
    }

    private final Status status;
    private final SummaryScope scope;
    private final SummaryResponse summary;
    private final SummaryError error;
    private final String lastSummaryId;

    private SummaryUiState(
            Status status,
            SummaryScope scope,
            SummaryResponse summary,
            SummaryError error,
            String lastSummaryId) {
        this.status = status;
        this.scope = scope;
        this.summary = summary;
        this.error = error;
        this.lastSummaryId = lastSummaryId;
    }

    public static SummaryUiState idle(SummaryScope scope, String lastSummaryId) {
        return new SummaryUiState(Status.IDLE, scope, null, null, lastSummaryId);
    }

    public static SummaryUiState loading(SummaryScope scope, String lastSummaryId) {
        return new SummaryUiState(Status.LOADING, scope, null, null, lastSummaryId);
    }

    public static SummaryUiState ready(SummaryScope scope, SummaryResponse summary) {
        return new SummaryUiState(Status.READY, scope, summary, null,
                summary == null ? null : summary.getSummaryId());
    }

    public static SummaryUiState error(
            SummaryScope scope,
            SummaryError error,
            String lastSummaryId) {
        return new SummaryUiState(Status.ERROR, scope, null, error, lastSummaryId);
    }

    public Status getStatus() {
        return status;
    }

    public SummaryScope getScope() {
        return scope;
    }

    public SummaryResponse getSummary() {
        return summary;
    }

    public SummaryError getError() {
        return error;
    }

    public String getLastSummaryId() {
        return lastSummaryId;
    }
}
