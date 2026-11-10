package com.nexachat.auth.google;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Verifies a Google ID token and extracts the identity claims (signature,
 * issuer, audience, expiry and nonce), failing closed with one generic error.
 *
 * <p>The nonce check is the replay guard: the client must send the same
 * single-use value it received from {@code POST /api/v1/auth/google/nonce},
 * and the service consumes that value before issuing a session.
 */
@Component
public class GoogleIdTokenVerifier {

    /** A Google ID token is normally ~1 KB; anything far larger is not a JWT we issued a nonce for. */
    private static final int MAX_TOKEN_LENGTH = 8192;

    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(JwtDecoder googleIdTokenDecoder) {
        this.decoder = googleIdTokenDecoder;
    }

    public GoogleIdTokenClaims verify(String rawToken, String expectedNonce) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
            throw failed();
        }
        if (expectedNonce == null || expectedNonce.isBlank() || expectedNonce.length() > 512) {
            throw failed();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(rawToken);
        } catch (JwtException | IllegalArgumentException exception) {
            throw failed();
        }
        String subject = jwt.getClaimAsString("sub");
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw failed();
        }
        String nonce = jwt.getClaimAsString("nonce");
        if (nonce == null || !MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.UTF_8),
                expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw failed();
        }
        String email = jwt.getClaimAsString("email");
        if (email != null && email.length() > 320) {
            email = email.substring(0, 320);
        }
        Object verified = jwt.getClaim("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified)
                || "true".equals(String.valueOf(verified).toLowerCase(Locale.ROOT));
        return new GoogleIdTokenClaims(subject, email, emailVerified);
    }

    private static ApiException failed() {
        return ApiException.of(ApiErrorCode.GOOGLE_SIGN_IN_FAILED, "Google sign-in could not be completed");
    }
}
