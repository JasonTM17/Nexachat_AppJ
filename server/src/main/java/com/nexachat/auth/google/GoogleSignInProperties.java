package com.nexachat.auth.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code nexachat.google.signin.*}.
 *
 * <p>The feature is disabled by default; when enabled the web client ID (the
 * OAuth 2.0 "Web application" client that also serves the Android Credential
 * Manager request) becomes mandatory and startup fails closed without it.
 *
 * @param enabled         master switch; every endpoint answers 404 when false
 * @param webClientId     expected {@code aud} of the Google ID token
 * @param jwksUri          Google's public JWKS (rotated by Google, cached by Nimbus)
 * @param nonceTtlSeconds  lifetime of an issued sign-in nonce
 * @param androidClientIds required while enabled; Android OAuth client IDs
 *                         trusted as the token's {@code azp} when Google sets it
 * @param signupEnabled    when true with {@code enabled}, an unknown Google {@code sub}
 *                         may create a new NexaChat account on {@code POST /auth/google}
 * @param issuer           non-empty exact allow-list of accepted ID-token {@code iss} claim values
 */
@ConfigurationProperties(prefix = "nexachat.google.signin")
public record GoogleSignInProperties(
        boolean enabled,
        String webClientId,
        String jwksUri,
        long nonceTtlSeconds,
        List<String> androidClientIds,
        boolean signupEnabled,
        List<String> issuer) {

    public GoogleSignInProperties {
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = "https://www.googleapis.com/oauth2/v3/certs";
        }
        if (nonceTtlSeconds <= 0) {
            nonceTtlSeconds = 300;
        }
        androidClientIds = androidClientIds == null
                ? List.of()
                : androidClientIds.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();
        issuer = issuer == null
                ? GoogleIdTokenValidators.DEFAULT_ISSUERS
                : issuer.stream()
                        .map(value -> value == null ? "" : value.trim())
                        .toList();
        if (issuer.isEmpty() || issuer.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "nexachat.google.signin.issuer must contain at least one non-blank issuer");
        }
    }
}
