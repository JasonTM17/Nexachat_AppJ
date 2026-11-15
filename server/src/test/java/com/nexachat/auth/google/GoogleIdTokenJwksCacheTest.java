package com.nexachat.auth.google;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWKS fetch discipline of the production decoder: the key set is fetched once
 * and reused across verifications, a token naming an unknown {@code kid} forces
 * exactly one refresh (Google key rotation), and a key that stays unpublished
 * keeps failing closed instead of retrying forever.
 */
class GoogleIdTokenJwksCacheTest {

    @Test
    void repeatedVerificationFetchesTheKeySetOnce() {
        try (JwksServer jwks = JwksServer.start(List.of(GoogleTestKeys.keyA()))) {
            JwtDecoder decoder = decoder(jwks);

            for (int attempt = 0; attempt < 3; attempt++) {
                String nonce = "nonce-" + attempt;
                String token = GoogleTestKeys.token(claims -> claims.nonce = nonce);
                assertThat(decoder.decode(token).getSubject()).isEqualTo(GoogleTestKeys.SUBJECT);
            }

            assertThat(jwks.requestCount())
                    .as("the key set is cached across verifications")
                    .isEqualTo(1);
        }
    }

    @Test
    void anUnknownKeyIdRefreshesOnceAndAcceptsTheRotatedKey() {
        try (JwksServer jwks = JwksServer.start(List.of(GoogleTestKeys.keyA()))) {
            JwtDecoder decoder = decoder(jwks);
            decoder.decode(GoogleTestKeys.token("nonce-0"));
            int fetchesAfterWarmup = jwks.requestCount();

            jwks.publish(List.of(GoogleTestKeys.keyB()));
            String rotated = GoogleTestKeys.token(claims -> {
                claims.nonce = "nonce-1";
                claims.key = GoogleTestKeys.keyB();
            });

            assertThat(decoder.decode(rotated).getSubject()).isEqualTo(GoogleTestKeys.SUBJECT);
            assertThat(jwks.requestCount())
                    .as("an unknown kid triggers exactly one refresh")
                    .isEqualTo(fetchesAfterWarmup + 1);
        }
    }

    @Test
    void aKeyThatStaysUnpublishedKeepsFailingClosed() {
        try (JwksServer jwks = JwksServer.start(List.of(GoogleTestKeys.keyA(), GoogleTestKeys.keyB()))) {
            JwtDecoder decoder = decoder(jwks);
            decoder.decode(GoogleTestKeys.token("nonce-0"));
            int fetchesAfterWarmup = jwks.requestCount();

            String forgedKeyToken = GoogleTestKeys.token(claims -> {
                claims.nonce = "nonce-1";
                claims.key = GoogleTestKeys.keyC();
            });

            assertThatThrownBy(() -> decoder.decode(forgedKeyToken))
                    .isInstanceOf(JwtException.class);
            // The unknown kid may trigger one refresh, but never an unbounded
            // retry loop, and the token must still be rejected afterwards.
            assertThat(jwks.requestCount()).isLessThanOrEqualTo(fetchesAfterWarmup + 1);
        }
    }

    @Test
    void aFailedRefreshDuringRotationFailsClosedAndRecovers() {
        try (JwksServer jwks = JwksServer.start(List.of(GoogleTestKeys.keyA()))) {
            JwtDecoder decoder = decoder(jwks);
            decoder.decode(GoogleTestKeys.token("nonce-0"));

            jwks.failWith(500);
            String rotated = GoogleTestKeys.token(claims -> {
                claims.nonce = "nonce-1";
                claims.key = GoogleTestKeys.keyB();
            });
            assertThatThrownBy(() -> decoder.decode(rotated)).isInstanceOf(JwtException.class);

            jwks.publish(List.of(GoogleTestKeys.keyA(), GoogleTestKeys.keyB()));
            assertThat(decoder.decode(rotated).getSubject()).isEqualTo(GoogleTestKeys.SUBJECT);
        }
    }

    private static JwtDecoder decoder(JwksServer jwks) {
        return GoogleSignInConfiguration.decoderFor(new GoogleSignInProperties(
                true, GoogleTestKeys.AUDIENCE, jwks.uri(), 300,
                List.of(GoogleTestKeys.ANDROID_CLIENT_ID), false, null));
    }
}
