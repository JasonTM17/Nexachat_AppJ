package com.nexachat.core.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ApiErrorTest {

    @Test
    public void mapsCredentialCodesToDistinctDomainErrors() {
        assertEquals(ApiError.InvalidCredentials.INSTANCE, ApiError.from(401, "INVALID_CREDENTIALS"));
        assertEquals(ApiError.WrongPassword.INSTANCE, ApiError.from(401, "WRONG_PASSWORD"));
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

    @Test
    public void unknownHttpErrorsRetainStatusForRetryPolicy() {
        ApiError.Unknown failure = (ApiError.Unknown) ApiError.from(503, "SERVER_BUSY");

        assertEquals("SERVER_BUSY", failure.getCode());
        assertEquals(503, failure.getHttpStatus());
        assertEquals(-1, new ApiError.Unknown("LOCAL_FAILURE").getHttpStatus());
    }

    @Test
    public void mailUnavailableRequiresExplicitCode() {
        assertEquals(ApiError.MailUnavailable.INSTANCE, ApiError.from(503, "MAIL_UNAVAILABLE"));
        assertTrue(ApiError.from(503, null) instanceof ApiError.Unknown);
    }

    @Test
    public void mapsEmailAlreadyInUse() {
        assertEquals(ApiError.EmailAlreadyInUse.INSTANCE, ApiError.from(409, "EMAIL_ALREADY_IN_USE"));
    }
}
