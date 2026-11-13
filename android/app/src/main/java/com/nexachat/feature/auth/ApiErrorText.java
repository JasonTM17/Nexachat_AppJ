package com.nexachat.feature.auth;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/** Maps domain errors to localized UI resources; server messages are never shown directly. */
public final class ApiErrorText {
    private ApiErrorText() {}

    @StringRes public static int resource(@NonNull ApiError error) {
        if (error instanceof ApiError.InvalidCredentials) return R.string.error_invalid_credentials;
        if (error instanceof ApiError.WrongPassword) return R.string.error_wrong_password;
        if (error instanceof ApiError.PhoneExists) return R.string.error_phone_exists;
        if (error instanceof ApiError.RateLimited) return R.string.error_rate_limited;
        if (error instanceof ApiError.MailUnavailable) return R.string.recovery_email_mail_unavailable;
        if (error instanceof ApiError.EmailAlreadyInUse) return R.string.recovery_email_in_use;
        if (error instanceof ApiError.RecoveryEmailInvalid) return R.string.recovery_email_invalid;
        if (error instanceof ApiError.InvalidOrExpiredCode) {
            return R.string.forgot_password_code_invalid;
        }
        if (error instanceof ApiError.SessionExpired) return R.string.session_expired_sign_in_again;
        if (error instanceof ApiError.Network) return R.string.error_network;
        if (error instanceof ApiError.Unknown unknown) {
            String code = unknown.getCode();
            if ("GOOGLE_ACCOUNT_NOT_LINKED".equals(code)) return R.string.error_google_not_linked;
            if ("GOOGLE_ACCOUNT_ALREADY_LINKED".equals(code)) return R.string.error_google_already_linked;
            if ("GOOGLE_SIGN_IN_FAILED".equals(code)) return R.string.error_google_sign_in;
            if (com.nexachat.core.auth.GoogleSignInFlow.CODE_UNAVAILABLE.equals(code)) {
                return R.string.error_google_unavailable;
            }
            if ("LAST_SIGN_IN_METHOD".equals(code)) return R.string.error_last_sign_in_method;
            if ("NO_PASSWORD_SET".equals(code)) return R.string.error_no_password_set;
            if (com.nexachat.core.auth.GoogleSignInFlow.CODE_NO_ACCOUNT.equals(code)) {
                return R.string.error_google_no_account;
            }
        }
        return R.string.error_generic;
    }

    @NonNull public static CharSequence text(@NonNull Context context, @NonNull ApiError error) {
        return context.getText(resource(error));
    }
}
