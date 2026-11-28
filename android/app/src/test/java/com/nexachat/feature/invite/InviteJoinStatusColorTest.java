package com.nexachat.feature.invite;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InviteJoinStatusColorTest {

    @Test
    public void failuresUseTheErrorColor() {
        for (InviteFailure failure : InviteFailure.values()) {
            if (failure == InviteFailure.SIGN_IN_REQUIRED) {
                continue;
            }
            assertEquals("failure " + failure,
                    com.google.android.material.R.attr.colorError,
                    InviteJoinFragment.statusTextColorAttr(failure));
        }
    }

    @Test
    public void signInRequiredUsesTheSameColorAsPlainStatus() {
        assertEquals(com.google.android.material.R.attr.colorOnSurfaceVariant,
                InviteJoinFragment.statusTextColorAttr(InviteFailure.SIGN_IN_REQUIRED));
    }

    @Test
    public void plainStatusesKeepTheVariantColor() {
        assertEquals(com.google.android.material.R.attr.colorOnSurfaceVariant,
                InviteJoinFragment.statusTextColorAttr(null));
    }
}
