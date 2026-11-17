package com.nexachat.feature.profile;

/**
 * Pure visibility rules for the sign-in methods card on the profile screen.
 * The phone row is always visible — it is the entry point for adding,
 * replacing or removing the number — while the Google row depends on
 * sign-in configuration, so the divider only exists when both rows render.
 */
public final class ProfileSignInMethodRules {

    private ProfileSignInMethodRules() {
    }

    /** The phone divider exists only between the Google and phone rows. */
    public static boolean phoneDividerVisible(boolean googleVisible) {
        return googleVisible;
    }
}
