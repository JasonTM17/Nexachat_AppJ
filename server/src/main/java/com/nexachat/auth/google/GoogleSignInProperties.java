package com.nexachat.auth.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code nexachat.google.signin.*}.
 *
 * <p>The feature is disabled by default; when enabled the web client ID (the
 * OAuth 2.0 "Web application" client that also serves the Android Credential
 * Manager request) becomes mandatory and startup fails closed without it.
 *
 * @param enabled         master switch; every endpoint answers 404 when false
 * @param webClientId     expected {@code aud} of the Google ID token
 * @param jwksUri         Google's public JWKS (rotated by Google, cached by Nimbus)
 * @param nonceTtlSeconds lifetime of an issued sign-in nonce
 */
@ConfigurationProperties(prefix = "nexachat.google.signin")
public record GoogleSignInProperties(
        boolean enabled,
        String webClientId,
        String jwksUri,
        long nonceTtlSeconds) {

    public GoogleSignInProperties {
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = "https://www.googleapis.com/oauth2/v3/certs";
        }
        if (nonceTtlSeconds <= 0) {
            nonceTtlSeconds = 300;
        }
    }
}
