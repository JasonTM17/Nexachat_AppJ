package com.nexachat.feature.auth;

import androidx.navigation.NavOptions;
import com.nexachat.R;

/** Replaces the auth root so completed forms never remain as Back destinations. */
public final class AuthNav {
    private AuthNav() {}

    public static NavOptions signedInOptions() {
        return new NavOptions.Builder().setPopUpTo(R.id.nav_main, true).build();
    }

    public static NavOptions authTabSwitchOptions() {
        return new NavOptions.Builder()
                .setPopUpTo(R.id.nav_main, true)
                .setLaunchSingleTop(true)
                .build();
    }
}
