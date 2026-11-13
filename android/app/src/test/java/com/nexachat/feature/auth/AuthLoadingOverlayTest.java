package com.nexachat.feature.auth;

import com.nexachat.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AuthLoadingOverlayTest {

    @Test
    public void contentDescriptionIsNotGoogleContinueLabel() {
        assertNotEquals(
                R.string.action_continue_with_google,
                AuthLoadingOverlay.contentDescriptionResId());
    }

    @Test
    public void contentDescriptionUsesGenericLoadingToken() {
        assertEquals(R.string.voice_status_loading, AuthLoadingOverlay.contentDescriptionResId());
    }
}
