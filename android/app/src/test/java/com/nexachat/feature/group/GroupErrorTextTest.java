package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import org.junit.Test;

public final class GroupErrorTextTest {

    @Test
    public void resource_mapsKnownApiErrorsToStringResources() {
        assertEquals(R.string.error_network, GroupErrorText.resource(new ApiError.Network()));
        assertEquals(
                R.string.error_invalid_credentials,
                GroupErrorText.resource(ApiError.InvalidCredentials.INSTANCE));
        assertEquals(R.string.error_phone_exists, GroupErrorText.resource(ApiError.PhoneExists.INSTANCE));
        assertEquals(R.string.error_rate_limited, GroupErrorText.resource(ApiError.RateLimited.INSTANCE));
    }

    @Test
    public void resource_unknownErrorsFallBackToGeneric() {
        assertEquals(R.string.error_generic, GroupErrorText.resource(new ApiError.Unknown("X")));
    }

    @Test
    public void resource_matchesGroupErrorIdsContract() {
        ApiError[] errors = {
            ApiError.InvalidCredentials.INSTANCE,
            ApiError.PhoneExists.INSTANCE,
            ApiError.RateLimited.INSTANCE,
            new ApiError.Network(),
            new ApiError.Unknown("FUTURE"),
        };
        for (ApiError error : errors) {
            assertEquals(GroupErrorIds.messageId(error), GroupErrorText.resource(error));
        }
    }
}
