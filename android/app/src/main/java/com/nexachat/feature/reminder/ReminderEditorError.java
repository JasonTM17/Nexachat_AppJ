package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;

/** One-shot validation/server failure surfaced by the reminder editor and row actions. */
public enum ReminderEditorError {
    TITLE_REQUIRED,
    TITLE_TOO_LONG,
    TIME_REQUIRED,
    TIME_NOT_FUTURE,
    TIME_TOO_FAR,
    FORBIDDEN,
    NOT_MEMBER,
    NOT_ADMIN,
    NOT_FOUND,
    VALIDATION,
    LIMIT,
    RATE_LIMITED,
    CONFLICT,
    OFFLINE,
    UNKNOWN;

    @NonNull
    public static ReminderEditorError fromFailure(@NonNull ReminderRepository.Failure failure) {
        switch (failure) {
            case NOT_MEMBER:
                return NOT_MEMBER;
            case NOT_ADMIN:
                return NOT_ADMIN;
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
            case OFFLINE:
                return OFFLINE;
            default:
                return UNKNOWN;
        }
    }
}
