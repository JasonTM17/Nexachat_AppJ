package com.nexachat.core.ui;

/**
 * Whether the register confirmation field should show a mismatch error.
 */
public final class AuthConfirmRules {

    private AuthConfirmRules() {}

    public static boolean showMismatch(boolean checked, String password, String confirm) {
        if (!checked) {
            return false;
        }
        String left = password == null ? "" : password;
        String right = confirm == null ? "" : confirm;
        return !left.equals(right);
    }
}
