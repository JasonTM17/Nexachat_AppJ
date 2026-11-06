package com.nexachat.core.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ApiErrorTest {

    @Test
    public void mapsBothCredentialCodesToTheSameDomainError() {
        assertEquals(ApiError.InvalidCredentials.INSTANCE, ApiError.from(401, "INVALID_CREDENTIALS"));
        assertEquals(ApiError.InvalidCredentials.INSTANCE, ApiError.from(401, "WRONG_PASSWORD"));
    }

    @Test
    public void mapsPhoneExistsAndRateLimitCodes() {
        assertEquals(ApiError.PhoneExists.INSTANCE, ApiError.from(409, "PHONE_EXISTS"));
        assertEquals(ApiError.RateLimited.INSTANCE, ApiError.from(400, "RATE_LIMITED"));
    }

    @Test
    public void status429MapsToRateLimitEvenWithoutRecognizedCode() {
        assertEquals(ApiError.RateLimited.INSTANCE, ApiError.from(429, "SERVER_BUSY"));
        assertEquals(ApiError.RateLimited.INSTANCE, ApiError.from(429, null));
    }

    @Test
    public void unknownCodeIsPreservedAndNullRemainsNull() {
        assertEquals(new ApiError.Unknown("FUTURE_CODE"), ApiError.from(422, "FUTURE_CODE"));
        assertEquals(new ApiError.Unknown(null), ApiError.from(500, null));
    }
}
