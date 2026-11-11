package com.nexachat.feature.auth;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/** Pure login-banner presentation state for form errors and session expiry. */
public final class LoginErrorBannerState {

    private final boolean visible;
    @StringRes private final int messageRes;

    private LoginErrorBannerState(boolean visible, @StringRes int messageRes) {
        this.visible = visible;
        this.messageRes = messageRes;
    }

    public static LoginErrorBannerState resolve(boolean sessionExpired, @Nullable ApiError error) {
        if (error != null) {
            return new LoginErrorBannerState(true, ApiErrorText.resource(error));
        }
        return sessionExpired
                ? new LoginErrorBannerState(true, R.string.session_expired_sign_in_again)
                : new LoginErrorBannerState(false, 0);
    }

    public boolean isVisible() {
        return visible;
    }

    @StringRes
    public int getMessageRes() {
        return messageRes;
    }
}
