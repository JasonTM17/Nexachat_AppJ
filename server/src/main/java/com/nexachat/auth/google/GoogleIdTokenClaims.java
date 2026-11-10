package com.nexachat.auth.google;

/**
 * The only claims this service trusts from a Google ID token.
 *
 * @param subject       the immutable Google account identifier ({@code sub})
 * @param email         the email Google reported at link time; display only, never an identity key
 * @param emailVerified Google's {@code email_verified} flag, recorded but not a login requirement
 */
public record GoogleIdTokenClaims(String subject, String email, boolean emailVerified) {
}
