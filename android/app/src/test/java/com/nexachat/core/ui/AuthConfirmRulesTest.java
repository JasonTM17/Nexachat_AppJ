package com.nexachat.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuthConfirmRulesTest {

    @Test
    public void showMismatch_staysHiddenUntilTheFormIsChecked() {
        assertFalse(AuthConfirmRules.showMismatch(false, "password1", "other"));
    }

    @Test
    public void showMismatch_clearsWhenTheFirstPasswordIsEditedToMatch() {
        assertTrue(AuthConfirmRules.showMismatch(true, "password1", "password2"));
        assertFalse(AuthConfirmRules.showMismatch(true, "password2", "password2"));
    }

    @Test
    public void showMismatch_treatsNullAsEmpty() {
        assertFalse(AuthConfirmRules.showMismatch(true, null, ""));
        assertTrue(AuthConfirmRules.showMismatch(true, null, "x"));
    }
}
