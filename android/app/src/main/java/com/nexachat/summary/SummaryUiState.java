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
    private final Integer limit;
    private final SummaryResponse summary;
    private final SummaryError error;
    private final String lastSummaryId;

    private SummaryUiState(
            Status status,
            SummaryScope scope,
            Integer limit,
            SummaryResponse summary,
            SummaryError error,
            String lastSummaryId) {
        this.status = status;
        this.scope = scope;
        this.limit = limit;
        this.summary = summary;
        this.error = error;
        this.lastSummaryId = lastSummaryId;
    }

    public static SummaryUiState idle(SummaryScope scope, Integer limit, String lastSummaryId) {
        return new SummaryUiState(Status.IDLE, scope, limit, null, null, lastSummaryId);
    }

    public static SummaryUiState loading(SummaryScope scope, Integer limit, String lastSummaryId) {
        return new SummaryUiState(Status.LOADING, scope, limit, null, null, lastSummaryId);
    }

    public static SummaryUiState ready(SummaryScope scope, Integer limit, SummaryResponse summary) {
        return new SummaryUiState(Status.READY, scope, limit, summary, null,
                summary == null ? null : summary.getSummaryId());
    }

    public static SummaryUiState error(
            SummaryScope scope,
            Integer limit,
            SummaryError error,
            String lastSummaryId) {
        return new SummaryUiState(Status.ERROR, scope, limit, null, error, lastSummaryId);
    }

    public Status getStatus() {
        return status;
    }

    public SummaryScope getScope() {
        return scope;
    }

    /** The N of a LAST_N request; null for every other scope. */
    public Integer getLimit() {
        return limit;
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
