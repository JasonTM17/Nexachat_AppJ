package com.nexachat.feature.auth;

import com.nexachat.R;

import org.junit.Test;

import androidx.navigation.NavOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuthNavTest {

    @Test
    public void signedInNavigationRemovesTheLoginScreen() {
        NavOptions options = AuthNav.signedInOptions();
        assertEquals(R.id.loginFragment, options.getPopUpToId());
        assertTrue(options.isPopUpToInclusive());
    }
}
