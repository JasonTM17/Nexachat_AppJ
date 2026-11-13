package com.nexachat.feature.auth;

import com.nexachat.R;

import org.junit.Test;

import androidx.navigation.NavOptions;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuthNavTest {

    @Test
    public void inviteReturnAcceptsOnlyValidatedTokens() {
        String token = "A".repeat(43);
        assertEquals(token, AuthNav.validatedInviteToken(token));
        assertNull(AuthNav.validatedInviteToken(null));
        assertNull(AuthNav.validatedInviteToken(""));
        assertNull(AuthNav.validatedInviteToken("A".repeat(42)));
        assertNull(AuthNav.validatedInviteToken("A".repeat(44)));
        assertNull(AuthNav.validatedInviteToken("/".repeat(43)));
        assertNull(AuthNav.validatedInviteToken("nexachat://join/" + token));
    }

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
