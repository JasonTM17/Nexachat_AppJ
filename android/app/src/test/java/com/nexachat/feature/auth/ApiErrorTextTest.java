package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import org.junit.Test;

public final class ApiErrorTextTest {

    @Test
    public void resource_mapsKnownApiErrorsToStringResources() {
        assertEquals(
                R.string.error_invalid_credentials,
                ApiErrorText.resource(ApiError.InvalidCredentials.INSTANCE));
        assertEquals(R.string.error_phone_exists, ApiErrorText.resource(ApiError.PhoneExists.INSTANCE));
        assertEquals(
                R.string.error_wrong_password,
                ApiErrorText.resource(ApiError.WrongPassword.INSTANCE));
        assertEquals(R.string.error_rate_limited, ApiErrorText.resource(ApiError.RateLimited.INSTANCE));
        assertEquals(R.string.error_network, ApiErrorText.resource(new ApiError.Network()));
        assertEquals(
                R.string.recovery_email_mail_unavailable,
                ApiErrorText.resource(ApiError.MailUnavailable.INSTANCE));
        assertEquals(
                R.string.recovery_email_in_use,
                ApiErrorText.resource(ApiError.EmailAlreadyInUse.INSTANCE));
        assertEquals(
                R.string.recovery_email_invalid,
                ApiErrorText.resource(ApiError.RecoveryEmailInvalid.INSTANCE));
        assertEquals(
                R.string.session_expired_sign_in_again,
                ApiErrorText.resource(ApiError.SessionExpired.INSTANCE));
    }

    @Test
    public void resource_mapsGoogleAndIdentityErrors() {
        assertEquals(R.string.error_google_not_linked, ApiErrorText.resource(
                new ApiError.Unknown("GOOGLE_ACCOUNT_NOT_LINKED", 404)));
        assertEquals(R.string.error_google_already_linked, ApiErrorText.resource(
                new ApiError.Unknown("GOOGLE_ACCOUNT_ALREADY_LINKED", 409)));
        assertEquals(R.string.error_google_sign_in, ApiErrorText.resource(
                new ApiError.Unknown("GOOGLE_SIGN_IN_FAILED", 401)));
        assertEquals(R.string.error_last_sign_in_method, ApiErrorText.resource(
                new ApiError.Unknown("LAST_SIGN_IN_METHOD", 409)));
        assertEquals(R.string.error_no_password_set, ApiErrorText.resource(
                new ApiError.Unknown("NO_PASSWORD_SET", 409)));
        assertEquals(R.string.error_google_no_account, ApiErrorText.resource(
                new ApiError.Unknown(com.nexachat.core.auth.GoogleSignInFlow.CODE_NO_ACCOUNT)));
    }

    @Test
    public void resource_unknownErrorsFallBackToGeneric() {
        assertEquals(R.string.error_generic, ApiErrorText.resource(new ApiError.Unknown("X")));
        assertEquals(R.string.error_generic, ApiErrorText.resource(new ApiError.Unknown(null)));
    }

    @Test
    public void resource_matchesAuthErrorIdsContract() {
        ApiError[] errors = {
            ApiError.InvalidCredentials.INSTANCE,
            ApiError.WrongPassword.INSTANCE,
            ApiError.PhoneExists.INSTANCE,
            ApiError.InvalidOrExpiredCode.INSTANCE,
            ApiError.RateLimited.INSTANCE,
            new ApiError.Network(),
            ApiError.SessionExpired.INSTANCE,
            new ApiError.Unknown("FUTURE"),
            new ApiError.Unknown("GOOGLE_ACCOUNT_NOT_LINKED"),
            new ApiError.Unknown("GOOGLE_ACCOUNT_ALREADY_LINKED"),
            new ApiError.Unknown("GOOGLE_SIGN_IN_FAILED"),
            new ApiError.Unknown("LAST_SIGN_IN_METHOD"),
            new ApiError.Unknown("NO_PASSWORD_SET"),
            new ApiError.Unknown(com.nexachat.core.auth.GoogleSignInFlow.CODE_NO_ACCOUNT),
        };
        for (ApiError error : errors) {
            assertEquals(AuthErrorIds.messageId(error), ApiErrorText.resource(error));
        }
    }
}
