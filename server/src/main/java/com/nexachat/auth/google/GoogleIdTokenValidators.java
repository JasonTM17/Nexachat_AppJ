package com.nexachat.auth.google;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.Set;

/**
 * Validators applied to a Google ID token before any claim is trusted.
 *
 * <p>Google's documented checks (issuer, audience, expiry, signature) are
 * enforced by Nimbus; the issuer is the one place where Google accepts both the
 * scheme-less and the fully qualified value, so a custom validator replaces the
 * stock single-issuer one.
 */
public final class GoogleIdTokenValidators {

    /** The two accepted ID-token issuer spellings. */
    public static final Set<String> ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    /** Tolerated client/server clock skew, applied to {@code exp} and {@code nbf}. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private GoogleIdTokenValidators() {
    }

    /**
     * @param audience the OAuth web client ID the Android app requested the token for
     * @return a validator chain covering timestamp, issuer, audience and subject
     */
    public static OAuth2TokenValidator<Jwt> forAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Google web client ID is required");
        }
        OAuth2TokenValidator<Jwt> timestamp = new JwtTimestampValidator(CLOCK_SKEW);
        OAuth2TokenValidator<Jwt> issuer = jwt -> {
            String value = jwt.getClaimAsString("iss");
            return value != null && ISSUERS.contains(value)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN, "ID token issuer is not Google", null));
        };
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            java.util.List<String> values = jwt.getAudience();
            return values != null && values.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN, "ID token audience is not this app", null));
        };
        OAuth2TokenValidator<Jwt> subject = jwt -> {
            String value = jwt.getSubject();
            return value != null && !value.isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN, "ID token has no subject", null));
        };
        return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                timestamp, issuer, audienceValidator, subject);
    }
}
