package com.nexachat.feature.poll;

import androidx.annotation.NonNull;

/** One-shot vote/manage failures surfaced as a snackbar by the poll tab. */
public enum PollActionError {
    CLOSED,
    NOT_MEMBER,
    NOT_ADMIN,
    NOT_FOUND,
    VALIDATION,
    RATE_LIMITED,
    OFFLINE,
    UNKNOWN;

    @NonNull
    public static PollActionError fromFailure(@NonNull PollRepository.Failure failure) {
        switch (failure) {
            case CLOSED:
                return CLOSED;
            case NOT_MEMBER:
                return NOT_MEMBER;
            case NOT_ADMIN:
                return NOT_ADMIN;
            case NOT_FOUND:
                return NOT_FOUND;
            case VALIDATION:
                return VALIDATION;
            case RATE_LIMITED:
                return RATE_LIMITED;
            case OFFLINE:
                return OFFLINE;
            default:
                return UNKNOWN;
        }
    }
}
