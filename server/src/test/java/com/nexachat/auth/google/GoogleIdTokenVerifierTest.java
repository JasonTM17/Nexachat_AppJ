package com.nexachat.auth.google;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claim verification for Google ID tokens, exercised with locally signed
 * tokens and the production validator chain. The JWKS fetch path is covered by
 * {@link GoogleSignInIT}.
 */
class GoogleIdTokenVerifierTest {

    private static final String NONCE = "nonce-value-1";

    private final JwtDecoder decoder = decoder(GoogleTestKeys.keyA());
    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(decoder);

    @Test
    void acceptsAValidTokenAndExtractsIdentityClaims() {
        GoogleIdTokenClaims claims = verifier.verify(GoogleTestKeys.token(NONCE), NONCE);

        assertThat(claims.subject()).isEqualTo(GoogleTestKeys.SUBJECT);
        assertThat(claims.email()).isEqualTo(GoogleTestKeys.EMAIL);
        assertThat(claims.emailVerified()).isTrue();
    }

    @Test
    void acceptsTheSchemeLessIssuerAndAnUnverifiedEmail() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.issuer = "accounts.google.com";
            claims.emailVerified = false;
        });

        GoogleIdTokenClaims result = verifier.verify(token, NONCE);

        assertThat(result.subject()).isEqualTo(GoogleTestKeys.SUBJECT);
        assertThat(result.emailVerified()).isFalse();
    }

    @Test
    void rejectsATokenForAnotherAudience() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.audience = "some-other-app.apps.googleusercontent.com";
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsAnUntrustedAuthorizedPartyEvenWhenTheAudienceMatches() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.mutate = builder -> builder.claim("azp", "untrusted-client.apps.googleusercontent.com");
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsAnAdditionalUntrustedAudience() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.mutate = builder -> builder.audience(List.of(
                    GoogleTestKeys.AUDIENCE, "untrusted-client.apps.googleusercontent.com"));
        });

        assertFailed(token, NONCE);
    }

    @Test
    void acceptsATrustedAndroidAuthorizedParty() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.mutate = builder -> builder.claim("azp", GoogleTestKeys.ANDROID_CLIENT_ID);
        });

        assertThat(verifier.verify(token, NONCE).subject()).isEqualTo(GoogleTestKeys.SUBJECT);
    }

    @Test
    void rejectsAnIssuedAtBeyondTheClockSkew() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.issuedAt = Instant.now().plusSeconds(120);
            claims.expiresAt = Instant.now().plusSeconds(300);
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.issuer = "https://evil.example.com";
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsAnExpiredTokenOutsideTheClockSkew() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.issuedAt = Instant.now().minusSeconds(600);
            claims.expiresAt = Instant.now().minusSeconds(120);
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsATokenSignedByAnUnknownKey() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.key = GoogleTestKeys.keyB();
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsAMismatchedNonce() {
        assertFailed(GoogleTestKeys.token("another-nonce"), NONCE);
        assertFailed(GoogleTestKeys.token("another-nonce"), null);
    }

    @Test
    void rejectsATokenWithoutSubject() {
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = NONCE;
            claims.subject = null;
        });

        assertFailed(token, NONCE);
    }

    @Test
    void rejectsOversizedAndMalformedTokens() {
        assertFailed("x".repeat(9000), NONCE);
        assertFailed("not-a-jwt", NONCE);
        assertFailed(null, NONCE);
    }

    private void assertFailed(String token, String nonce) {
        assertThatThrownBy(() -> verifier.verify(token, nonce))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getCode())
                        .isEqualTo(ApiErrorCode.GOOGLE_SIGN_IN_FAILED));
    }

    private static JwtDecoder decoder(RSAKey key) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(
                            (java.security.interfaces.RSAPublicKey) key.toRSAPublicKey())
                    .build();
            decoder.setJwtValidator(GoogleIdTokenValidators.forAudience(
                    GoogleTestKeys.AUDIENCE, List.of(GoogleTestKeys.ANDROID_CLIENT_ID)));
            return decoder;
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new IllegalStateException("Could not expose the test public key", exception);
        }
    }
}
