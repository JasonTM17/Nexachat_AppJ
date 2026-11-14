package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProfileSignInMethodRulesTest {

    @Test
    public void phoneRowVisibleWhenNumberPresent() {
        assertTrue(ProfileSignInMethodRules.phoneRowVisible("+84 912 345 678"));
    }

    @Test
    public void phoneRowHiddenWhenMissingOrBlank() {
        assertFalse(ProfileSignInMethodRules.phoneRowVisible(null));
        assertFalse(ProfileSignInMethodRules.phoneRowVisible(""));
        assertFalse(ProfileSignInMethodRules.phoneRowVisible("   "));
    }

    @Test
    public void phoneDividerOnlyBetweenTwoVisibleRows() {
        assertTrue(ProfileSignInMethodRules.phoneDividerVisible(true, "+84912345678"));
        assertFalse(ProfileSignInMethodRules.phoneDividerVisible(false, "+84912345678"));
        assertFalse(ProfileSignInMethodRules.phoneDividerVisible(true, null));
        assertFalse(ProfileSignInMethodRules.phoneDividerVisible(true, " "));
    }
}
