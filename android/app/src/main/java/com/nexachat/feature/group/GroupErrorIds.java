package com.nexachat.feature.group;

import androidx.annotation.StringRes;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/** Maps an [ApiError] to its user-facing string resource id for group surfaces. */
public final class GroupErrorIds {

    private GroupErrorIds() {}

    @StringRes
    public static int messageId(ApiError error) {
        if (error instanceof ApiError.InvalidCredentials) {
            return R.string.error_invalid_credentials;
        }
        if (error instanceof ApiError.PhoneExists) {
            return R.string.error_phone_exists;
        }
        if (error instanceof ApiError.RateLimited) {
            return R.string.error_rate_limited;
        }
        if (error instanceof ApiError.Network) {
            return R.string.error_network;
        }
        return R.string.error_generic;
    }
}
