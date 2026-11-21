package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    static final int MAX_TURNS = 8;

    private final Status status;
    private final String draft;
    private final List<AskTurn> turns;

    private AskUiState(Status status, String draft, List<AskTurn> turns) {
        this.status = Objects.requireNonNull(status, "status");
        this.draft = Objects.requireNonNull(draft, "draft");
        this.turns = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(turns, "turns")));
    }

    public static AskUiState idle(String draft) {
        return idle(draft, List.of());
    }

    public static AskUiState idle(String draft, List<AskTurn> turns) {
        return fromTurns(draft, turns, false);
    }

    public static AskUiState loading(String draft, List<AskTurn> turns) {
        return new AskUiState(Status.LOADING, draft, turns);
    }

    static AskUiState fromTurns(String draft, List<AskTurn> turns, boolean loading) {
        if (loading) {
            return loading(draft, turns);
        }
        if (turns == null || turns.isEmpty()) {
            return new AskUiState(Status.IDLE, draft, List.of());
        }
        AskTurn last = turns.get(turns.size() - 1);
        return new AskUiState(statusOf(last), draft, turns);
    }

    static List<AskTurn> append(List<AskTurn> turns, AskTurn next) {
        List<AskTurn> copy = new ArrayList<>(turns == null ? List.of() : turns);
        copy.add(Objects.requireNonNull(next, "next"));
        while (copy.size() > MAX_TURNS) {
            copy.remove(0);
        }
        return copy;
    }

    private static Status statusOf(AskTurn turn) {
        return switch (turn.getStatus()) {
            case ANSWER -> Status.ANSWER;
            case NOT_FOUND -> Status.NOT_FOUND;
            case ERROR -> Status.ERROR;
            case RATE_LIMITED -> Status.RATE_LIMITED;
            case CONSENT_REQUIRED -> Status.CONSENT_REQUIRED;
        };
    }

    public Status getStatus() {
        return status;
    }

    public String getDraft() {
        return draft;
    }

    public List<AskTurn> getTurns() {
        return turns;
    }

    public boolean suggestionsVisible() {
        return status != Status.LOADING && turns.isEmpty() && draft.trim().isEmpty();
    }

    @Nullable
    public AskTurn lastTurn() {
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }

    @Nullable
    public AskResponse getResponse() {
        AskTurn last = lastTurn();
        return last == null ? null : last.getResponse();
    }

    @Nullable
    public AskError getError() {
        AskTurn last = lastTurn();
        return last == null ? null : last.getError();
    }

    @Nullable
    public Long getRetryAfterSeconds() {
        AskTurn last = lastTurn();
        return last == null ? null : last.getRetryAfterSeconds();
    }

    /** True when the latest turn omitted some eligible messages. */
    public boolean isContextOmitted() {
        AskTurn last = lastTurn();
        return last != null && last.isContextOmitted();
    }
}
