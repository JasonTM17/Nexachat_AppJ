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
        assertEquals(R.string.error_rate_limited, ApiErrorText.resource(ApiError.RateLimited.INSTANCE));
        assertEquals(R.string.error_network, ApiErrorText.resource(new ApiError.Network()));
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
            ApiError.PhoneExists.INSTANCE,
            ApiError.RateLimited.INSTANCE,
            new ApiError.Network(),
            new ApiError.Unknown("FUTURE"),
        };
        for (ApiError error : errors) {
            assertEquals(AuthErrorIds.messageId(error), ApiErrorText.resource(error));
        }
    }
}
