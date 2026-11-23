package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;

/** One-shot validation/server failure surfaced by the schedule sheet and row actions. */
public enum ScheduledMessageEditorError {
    BODY_REQUIRED,
    BODY_TOO_LONG,
    TIME_REQUIRED,
    TIME_TOO_SOON,
    FORBIDDEN,
    NOT_MEMBER,
    NOT_FOUND,
    VALIDATION,
    LIMIT,
    RATE_LIMITED,
    CONFLICT,
    DISPATCHING,
    E2EE,
    OFFLINE,
    UNKNOWN;

    @NonNull
    public static ScheduledMessageEditorError fromFailure(
            @NonNull ScheduledMessageRepository.Failure failure) {
        switch (failure) {
            case NOT_MEMBER:
                return NOT_MEMBER;
            case FORBIDDEN:
                return FORBIDDEN;
            case NOT_FOUND:
                return NOT_FOUND;
            case VALIDATION:
                return VALIDATION;
            case LIMIT:
                return LIMIT;
            case RATE_LIMITED:
                return RATE_LIMITED;
            case CONFLICT:
                return CONFLICT;
            case DISPATCHING:
                return DISPATCHING;
            case E2EE:
                return E2EE;
            case OFFLINE:
                return OFFLINE;
            default:
                return UNKNOWN;
        }
    }
}
