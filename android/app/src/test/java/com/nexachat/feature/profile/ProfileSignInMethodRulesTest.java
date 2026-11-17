package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProfileSignInMethodRulesTest {

    @Test
    public void phoneDividerShownWhenGoogleRowVisible() {
        assertTrue(ProfileSignInMethodRules.phoneDividerVisible(true));
    }

    @Test
    public void phoneDividerHiddenWithoutGoogleRow() {
        // With the Google row gone the phone row follows the recovery-email
        // row directly; a divider there would float under the card edge.
        assertFalse(ProfileSignInMethodRules.phoneDividerVisible(false));
    }
}
