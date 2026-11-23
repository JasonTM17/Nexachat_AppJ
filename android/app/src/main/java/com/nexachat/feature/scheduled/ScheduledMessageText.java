package com.nexachat.feature.scheduled;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.nexachat.R;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;

/** Localized scheduled-message labels and failure copy. */
public final class ScheduledMessageText {

    private ScheduledMessageText() { }

    @NonNull
    public static String dateTime(@NonNull Context context, @NonNull Instant instant) {
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT,
                context.getResources().getConfiguration().getLocales().get(0));
        return format.format(Date.from(instant));
    }

    @NonNull
    static String dateOnly(@NonNull Context context, @NonNull Instant instant) {
        return android.text.format.DateFormat.getMediumDateFormat(context)
                .format(Date.from(instant));
    }

    @NonNull
    static String timeOnly(@NonNull Context context, @NonNull Instant instant) {
        return android.text.format.DateFormat.getTimeFormat(context)
                .format(Date.from(instant));
    }

    /** Status line for one row; FAILED rows surface the server failure reason. */
    @NonNull
    static String statusLine(@NonNull Context context, @NonNull ScheduledMessage message) {
        switch (message.getStatus()) {
            case PENDING:
                return context.getString(R.string.scheduled_status_pending);
            case SENT:
                return context.getString(R.string.scheduled_status_sent);
            case CANCELED:
                return context.getString(R.string.scheduled_status_canceled);
            case FAILED:
            default:
                return context.getString(failureReason(message.getFailureCode()));
        }
    }

    @StringRes
    static int failureReason(@Nullable String failureCode) {
        if (failureCode == null) {
            return R.string.scheduled_failure_generic;
        }
        switch (failureCode) {
            case "NOT_MEMBER":
                return R.string.scheduled_failure_not_member;
            case "E2EE_REQUIRED":
            case "E2EE_NOT_ENABLED":
            case "E2EE_MEDIA_UNSUPPORTED":
                return R.string.scheduled_failure_e2ee;
            case "VALIDATION_FAILED":
                return R.string.scheduled_failure_validation;
            case "SCHEDULED_LIMIT":
                return R.string.scheduled_failure_limit;
            case "ACCOUNT_DELETED":
                return R.string.scheduled_failure_account_deleted;
            case "FAILED_RETRY_EXHAUSTED":
                return R.string.scheduled_failure_retries;
            default:
                return R.string.scheduled_failure_generic;
        }
    }

    @StringRes
    static int loadError(@NonNull ScheduledMessageRepository.Failure failure) {
        switch (failure) {
            case OFFLINE:
                return R.string.error_network;
            case NOT_MEMBER:
            case FORBIDDEN:
                return R.string.scheduled_error_not_member;
            case NOT_FOUND:
                return R.string.scheduled_error_not_found;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case VALIDATION:
                return R.string.scheduled_error_validation;
            case LIMIT:
                return R.string.scheduled_error_limit;
            case DISPATCHING:
                return R.string.scheduled_error_dispatching;
            case E2EE:
                return R.string.scheduled_error_e2ee;
            default:
                return R.string.scheduled_error;
        }
    }

    @StringRes
    static int editorError(@NonNull ScheduledMessageEditorError error) {
        switch (error) {
            case BODY_REQUIRED:
                return R.string.scheduled_error_body_required;
            case BODY_TOO_LONG:
                return R.string.scheduled_error_body_too_long;
            case TIME_REQUIRED:
                return R.string.scheduled_error_time_required;
            case TIME_TOO_SOON:
                return R.string.scheduled_error_time_too_soon;
            case NOT_MEMBER:
                return R.string.scheduled_error_not_member;
            case FORBIDDEN:
                return R.string.scheduled_error_forbidden;
            case NOT_FOUND:
                return R.string.scheduled_error_not_found;
            case VALIDATION:
                return R.string.scheduled_error_validation;
            case LIMIT:
                return R.string.scheduled_error_limit;
            case RATE_LIMITED:
                return R.string.error_rate_limited;
            case CONFLICT:
                return R.string.scheduled_error_conflict;
            case DISPATCHING:
                return R.string.scheduled_error_dispatching;
            case E2EE:
                return R.string.scheduled_error_e2ee;
            case OFFLINE:
                return R.string.error_network;
            default:
                return R.string.scheduled_error;
        }
    }
}
