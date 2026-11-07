package com.nexachat.feature.group;

import androidx.annotation.NonNull;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/** Maps group-operation errors to localized UI strings. */
public final class GroupErrorText {
    private GroupErrorText() {}
    public static int resource(@NonNull ApiError error) {
        if (error instanceof ApiError.Network) return R.string.error_network;
        if (error instanceof ApiError.InvalidCredentials) return R.string.error_invalid_credentials;
        if (error instanceof ApiError.PhoneExists) return R.string.error_phone_exists;
        if (error instanceof ApiError.RateLimited) return R.string.error_rate_limited;
        return R.string.error_generic;
    }
}
