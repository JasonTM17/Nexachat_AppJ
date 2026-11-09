package com.nexachat.feature.invite;

/** Safe UI error categories; raw server messages and request URLs stay private. */
public enum InviteFailure {
    SIGN_IN_REQUIRED,
    UNAVAILABLE,
    NOT_ALLOWED,
    RATE_LIMITED,
    OFFLINE,
    VALIDATION,
    UNKNOWN
}
