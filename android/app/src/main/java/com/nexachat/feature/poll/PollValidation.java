package com.nexachat.feature.poll;

/** Client-side create validation result; maps one-to-one to a localized message. */
public enum PollValidation {
    OK,
    QUESTION_REQUIRED,
    QUESTION_TOO_LONG,
    TOO_FEW_OPTIONS,
    TOO_MANY_OPTIONS,
    OPTION_REQUIRED,
    OPTION_TOO_LONG,
    DUPLICATE_OPTION,
    EXPIRY_PAST,
    EXPIRY_TOO_FAR
}
