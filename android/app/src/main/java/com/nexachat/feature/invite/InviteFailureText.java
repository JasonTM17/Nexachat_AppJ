package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import com.nexachat.R;

/** Maps invite outcomes to generic, localized messages that do not expose server text. */
public final class InviteFailureText {
    private InviteFailureText() { }

    public static int resource(@NonNull InviteFailure failure) {
        return switch (failure) {
            case SIGN_IN_REQUIRED -> R.string.invite_sign_in_required;
            case UNAVAILABLE -> R.string.invite_unavailable;
            case NOT_ALLOWED -> R.string.invite_not_allowed;
            case RATE_LIMITED -> R.string.invite_rate_limited;
            case OFFLINE -> R.string.invite_offline;
            case VALIDATION -> R.string.invite_validation_error;
            case UNKNOWN -> R.string.invite_generic_error;
            default -> R.string.invite_generic_error;
        };
    }
}
