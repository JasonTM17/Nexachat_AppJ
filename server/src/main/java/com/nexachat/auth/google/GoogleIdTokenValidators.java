package com.nexachat.auth.google;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validators applied to a Google ID token before any claim is trusted.
 *
 * <p>Beyond the stock signature/expiry checks this enforces:
 * <ul>
 *   <li>the issuer is one of Google's two accepted spellings;</li>
 *   <li>{@code aud} contains exactly this application's web client ID — a token
 *       minted for another client (even with ours added) is rejected;</li>
 *   <li>{@code azp}, when Google sets it, is one of our own clients (the web
 *       client or a configured Android client);</li>
 *   <li>{@code iat} exists and is not in the future beyond the clock skew;</li>
 *   <li>{@code sub} is present.</li>
 * </ul>
 */
public final class GoogleIdTokenValidators {

    /** The two accepted ID-token issuer spellings. */
    public static final Set<String> ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    /** Tolerated client/server clock skew, applied to {@code exp}, {@code nbf} and {@code iat}. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private GoogleIdTokenValidators() {
    }

    /** @param audience the OAuth web client ID the Android app requested the token for */
    public static OAuth2TokenValidator<Jwt> forAudience(String audience) {
        return forAudience(audience, List.of());
    }

    /**
     * @param audience         the OAuth web client ID the Android app requested the token for
     * @param androidClientIds additional trusted {@code azp} values (Android OAuth clients)
     */
    public static OAuth2TokenValidator<Jwt> forAudience(String audience,
                                                        List<String> androidClientIds) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Google web client ID is required");
        }
        Set<String> trustedAuthorizedParties = new LinkedHashSet<>();
        trustedAuthorizedParties.add(audience);
        if (androidClientIds != null) {
            androidClientIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(trustedAuthorizedParties::add);
        }

        OAuth2TokenValidator<Jwt> timestamp = new JwtTimestampValidator(CLOCK_SKEW);
        OAuth2TokenValidator<Jwt> issuer = jwt -> {
            String value = jwt.getClaimAsString("iss");
            return value != null && ISSUERS.contains(value)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("ID token issuer is not Google");
        };
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> values = jwt.getAudience();
            // Exactly one audience: a token that also names another client is not ours.
            return values != null && values.size() == 1 && values.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("ID token audience is not this app");
        };
        OAuth2TokenValidator<Jwt> authorizedParty = jwt -> {
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || azp.isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
            return trustedAuthorizedParties.contains(azp)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("ID token authorized party is not trusted");
        };
        OAuth2TokenValidator<Jwt> issuedAt = jwt -> {
            Instant value = jwt.getIssuedAt();
            if (value == null) {
                return failure("ID token has no issue time");
            }
            return value.isAfter(Instant.now().plus(CLOCK_SKEW))
                    ? failure("ID token issue time is in the future")
                    : OAuth2TokenValidatorResult.success();
        };
        OAuth2TokenValidator<Jwt> subject = jwt -> {
            String value = jwt.getSubject();
            return value != null && !value.isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : failure("ID token has no subject");
        };
        return new DelegatingOAuth2TokenValidator<>(
                timestamp, issuer, audienceValidator, authorizedParty, issuedAt, subject);
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
