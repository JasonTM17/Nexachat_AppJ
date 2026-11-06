package com.nexachat.feature.contacts;

import androidx.annotation.StringRes;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/**
 * Maps an {@link ApiError} to contacts/search string resources.
 * PhoneExists maps to {@code error_already_contact}, not the auth phone-exists copy.
 */
public final class ContactErrorIds {

    private ContactErrorIds() {}

    @StringRes
    public static int messageId(ApiError error) {
        if (error instanceof ApiError.InvalidCredentials) {
            return R.string.error_invalid_credentials;
        }
        if (error instanceof ApiError.PhoneExists) {
            return R.string.error_already_contact;
        }
        if (error instanceof ApiError.RateLimited) {
            return R.string.error_rate_limited;
        }
        if (error instanceof ApiError.Network) {
            return R.string.error_network;
        }
        if (error instanceof ApiError.Unknown unknown) {
            String code = unknown.getCode();
            if ("USER_NOT_FOUND".equals(code)) {
                return R.string.error_user_not_found;
            }
            if (SelfContactGuard.ERROR_CODE.equals(code)) {
                return R.string.error_cannot_add_self;
            }
        }
        return R.string.error_generic;
    }
}
