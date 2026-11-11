package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import org.junit.Test;

public final class LoginErrorBannerStateTest {

    @Test
    public void initialRenderKeepsSessionExpiredReasonVisible() {
        LoginErrorBannerState banner = LoginErrorBannerState.resolve(true, null);

        assertTrue(banner.isVisible());
        assertEquals(R.string.session_expired_sign_in_again, banner.getMessageRes());
    }

    @Test
    public void normalInitialRenderHasNoErrorBanner() {
        LoginErrorBannerState banner = LoginErrorBannerState.resolve(false, null);

        assertFalse(banner.isVisible());
    }

    @Test
    public void formErrorTakesPrecedenceOverSessionExpiredNotice() {
        LoginErrorBannerState banner = LoginErrorBannerState.resolve(
                true, ApiError.InvalidCredentials.INSTANCE);

        assertTrue(banner.isVisible());
        assertEquals(R.string.error_invalid_credentials, banner.getMessageRes());
    }

    @Test
    public void serverWakeStateTakesPrecedenceOverGenericNetworkError() {
        LoginErrorBannerState banner = LoginErrorBannerState.resolve(
                false, new ApiError.Network("timeout"), true);

        assertTrue(banner.isVisible());
        assertEquals(R.string.server_starting, banner.getMessageRes());
    }
}
