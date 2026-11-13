package com.nexachat.feature.auth;

import com.nexachat.R;

import org.junit.Test;

import androidx.navigation.NavOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuthNavTest {

    @Test
    public void signedInNavigationReplacesTheRootIncludingOnboarding() {
        NavOptions options = AuthNav.signedInOptions();
        assertEquals(R.id.nav_main, options.getPopUpToId());
        assertTrue(options.isPopUpToInclusive());
    }

    @Test
    public void authTabSwitchReplacesTheRootAndUsesLaunchSingleTop() {
        NavOptions options = AuthNav.authTabSwitchOptions();
        assertTrue(options.shouldLaunchSingleTop());
        assertEquals(R.id.nav_main, options.getPopUpToId());
        assertTrue(options.isPopUpToInclusive());
    }
}
