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
        if (error instanceof ApiError.PhoneExists) return R.string.error_phone_exists;
        if (error instanceof ApiError.RateLimited) return R.string.error_rate_limited;
        if (error instanceof ApiError.Network) return R.string.error_network;
        return R.string.error_generic;
    }

    @NonNull public static CharSequence text(@NonNull Context context, @NonNull ApiError error) {
        return context.getText(resource(error));
    }
}
