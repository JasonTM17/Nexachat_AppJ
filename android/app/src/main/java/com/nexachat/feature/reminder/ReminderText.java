package com.nexachat.feature.reminder;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;

/** Localized reminder labels and failure copy. */
final class ReminderText {

    private ReminderText() { }

    @NonNull
    static String dateTime(@NonNull Context context, @NonNull Instant instant) {
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT,
                context.getResources().getConfiguration().getLocales().get(0));
        return format.format(Date.from(instant));
    }

    @NonNull
    static String dateOnly(@NonNull Context context, @NonNull Instant instant) {
        return android.text.format.DateFormat.getMediumDateFormat(context).format(Date.from(instant));
    }

    @NonNull
    static String timeOnly(@NonNull Context context, @NonNull Instant instant) {
        return android.text.format.DateFormat.getTimeFormat(context).format(Date.from(instant));
    }

    @StringRes
    static int repeatLabel(@NonNull ReminderRepeat repeat) {
        switch (repeat) {
            case DAILY:
                return R.string.reminder_repeat_daily;
            case WEEKLY:
                return R.string.reminder_repeat_weekly;
            case MONTHLY:
                return R.string.reminder_repeat_monthly;
            default:
                return R.string.reminder_repeat_none;
        }
    }

    @StringRes
    static int loadError(@NonNull ReminderRepository.Failure failure) {
        switch (failure) {
            case OFFLINE:
                return R.string.error_network;
            case NOT_MEMBER:
            case FORBIDDEN:
                return R.string.reminder_error_not_member;
            case NOT_ADMIN:
                return R.string.reminder_error_not_admin;
            case NOT_FOUND:
                return R.string.reminder_error_not_found;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case VALIDATION:
                return R.string.reminder_error_validation;
            case LIMIT:
                return R.string.reminder_error_limit;
            default:
                return R.string.reminder_error;
        }
    }

    @StringRes
    static int editorError(@NonNull ReminderEditorError error) {
        switch (error) {
            case TITLE_REQUIRED:
                return R.string.reminder_error_title_required;
            case TITLE_TOO_LONG:
                return R.string.reminder_error_title_too_long;
            case TIME_REQUIRED:
                return R.string.reminder_error_time_required;
            case TIME_NOT_FUTURE:
                return R.string.reminder_error_time_past;
            case TIME_TOO_FAR:
                return R.string.reminder_error_time_too_far;
            case NOT_MEMBER:
                return R.string.reminder_error_not_member;
            case NOT_ADMIN:
                return R.string.reminder_error_not_admin;
            case FORBIDDEN:
                return R.string.reminder_error_forbidden;
            case NOT_FOUND:
                return R.string.reminder_error_not_found;
            case VALIDATION:
                return R.string.reminder_error_validation;
            case LIMIT:
                return R.string.reminder_error_limit;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case CONFLICT:
                return R.string.reminder_error_conflict;
            case OFFLINE:
                return R.string.error_network;
            default:
                return R.string.reminder_error;
        }
    }
}
