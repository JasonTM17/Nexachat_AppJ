package com.nexachat.feature.auth;

import androidx.navigation.NavOptions;
import com.nexachat.R;

/** Navigation used once a session exists, so Back does not reopen the sign-in form. */
public final class AuthNav {
    private AuthNav() {}

    public static NavOptions signedInOptions() {
        return new NavOptions.Builder().setPopUpTo(R.id.loginFragment, true).build();
    }

    public static NavOptions authTabSwitchOptions() {
        return new NavOptions.Builder().setLaunchSingleTop(true).build();
    }
}
