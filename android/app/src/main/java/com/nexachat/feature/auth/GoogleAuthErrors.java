package com.nexachat.feature.auth;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;

/** Shared Google auth error codes for the sign-in surfaces. */
public final class GoogleAuthErrors {

    public static final String GOOGLE_EMAIL_IN_USE = "GOOGLE_EMAIL_IN_USE";

    private GoogleAuthErrors() {}

    public static boolean isGoogleEmailInUse(@Nullable ApiError error) {
        return error instanceof ApiError.Unknown unknown
                && GOOGLE_EMAIL_IN_USE.equals(unknown.getCode());
    }
}
