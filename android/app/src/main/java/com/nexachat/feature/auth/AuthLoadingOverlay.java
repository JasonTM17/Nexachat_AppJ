package com.nexachat.feature.auth;

import androidx.annotation.StringRes;
import com.nexachat.R;

/** Content description for the full-screen auth loading overlay (phone and Google flows). */
public final class AuthLoadingOverlay {
    private AuthLoadingOverlay() {}

    @StringRes
    public static int contentDescriptionResId() {
        return R.string.voice_status_loading;
    }
}
