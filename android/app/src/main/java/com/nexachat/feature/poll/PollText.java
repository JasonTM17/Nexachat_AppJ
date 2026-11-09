package com.nexachat.feature.poll;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;
import android.text.format.DateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

/** Localized poll labels and failure copy. */
final class PollText {

    private PollText() { }

    @NonNull
    static String date(@NonNull Context context, @NonNull Instant instant) {
        java.text.DateFormat format = DateFormat.getMediumDateFormat(context);
        return format.format(Date.from(instant));
    }

    @StringRes
    static int validation(@NonNull PollValidation validation) {
        switch (validation) {
            case QUESTION_REQUIRED:
                return R.string.poll_error_question;
            case QUESTION_TOO_LONG:
                return R.string.poll_error_question_long;
            case TOO_FEW_OPTIONS:
                return R.string.poll_error_options_min;
            case TOO_MANY_OPTIONS:
                return R.string.poll_error_options_max;
            case OPTION_REQUIRED:
                return R.string.poll_error_option_blank;
            case OPTION_TOO_LONG:
                return R.string.poll_error_option_long;
            case DUPLICATE_OPTION:
                return R.string.poll_error_option_duplicate;
            case EXPIRY_PAST:
                return R.string.poll_error_expiry_past;
            case EXPIRY_TOO_FAR:
                return R.string.poll_error_expiry_far;
            default:
                return R.string.error_generic;
        }
    }

    @StringRes
    static int createError(@NonNull PollRepository.Failure failure) {
        switch (failure) {
            case OFFLINE:
                return R.string.error_network;
            case NOT_MEMBER:
                return R.string.poll_not_member;
            case NOT_A_GROUP:
                return R.string.poll_not_group;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case VALIDATION:
                return R.string.poll_error_create;
            default:
                return R.string.poll_error_create;
        }
    }

    @StringRes
    static int loadError(@NonNull PollRepository.Failure failure) {
        switch (failure) {
            case OFFLINE:
                return R.string.error_network;
            case NOT_MEMBER:
                return R.string.poll_not_member;
            case NOT_A_GROUP:
                return R.string.poll_not_group;
            case NOT_ADMIN:
                return R.string.poll_error_admin;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            default:
                return R.string.poll_error;
        }
    }

    @StringRes
    static int actionError(@NonNull PollActionError error) {
        switch (error) {
            case CLOSED:
                return R.string.poll_closed_vote;
            case NOT_MEMBER:
                return R.string.poll_not_member;
            case NOT_ADMIN:
                return R.string.poll_error_admin;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case OFFLINE:
                return R.string.error_network;
            default:
                return R.string.poll_error_manage;
        }
    }
}
