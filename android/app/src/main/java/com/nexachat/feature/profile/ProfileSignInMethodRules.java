package com.nexachat.feature.profile;

import androidx.annotation.Nullable;

/**
 * Pure visibility rules for the sign-in methods card on the profile screen.
 * The phone row depends on the loaded profile while the Google row depends on
 * sign-in configuration, so both inputs are evaluated together whenever either
 * source emits a new state.
 */
public final class ProfileSignInMethodRules {

    private ProfileSignInMethodRules() {
    }

    /** The phone sign-in row is shown only when the profile carries a phone number. */
    public static boolean phoneRowVisible(@Nullable String phone) {
        return phone != null && !phone.isBlank();
    }

    /** The phone divider exists only between two visible rows. */
    public static boolean phoneDividerVisible(boolean googleVisible, @Nullable String phone) {
        return googleVisible && phoneRowVisible(phone);
    }
}
