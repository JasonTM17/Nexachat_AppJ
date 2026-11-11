package com.nexachat.auth.google;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Locally generated Google-style RSA keys and signed ID tokens.
 *
 * <p>Tests never reach Google: the JWKS URI points at a local HTTP server that
 * publishes these public keys, so the production Nimbus decoder performs a real
 * signature, issuer, audience and expiry verification.
 */
public final class GoogleTestKeys {

    public static final String AUDIENCE = "test-web-client.apps.googleusercontent.com";
    public static final String ANDROID_CLIENT_ID = "test-android-client.apps.googleusercontent.com";
    public static final String SUBJECT = "google-subject-1";
    public static final String EMAIL = "user@example.com";

    private static final RSAKey KEY_A = generate("test-key-a");
    private static final RSAKey KEY_B = generate("test-key-b");
    private static final RSAKey KEY_C = generate("test-key-c");

    private GoogleTestKeys() {
    }

    public static RSAKey keyA() {
        return KEY_A;
    }

    public static RSAKey keyB() {
        return KEY_B;
    }

    /** Never published by the test JWKS: signature verification must reject it. */
    public static RSAKey keyC() {
        return KEY_C;
    }

    public static String jwks(List<RSAKey> keys) {
        List<com.nimbusds.jose.jwk.JWK> publicKeys = keys.stream()
                .map(RSAKey::toPublicJWK)
                .map(key -> (com.nimbusds.jose.jwk.JWK) key)
                .toList();
        return new JWKSet(publicKeys).toString();
    }

    public static String token(String nonce) {
        return token(keyA(), AUDIENCE, "https://accounts.google.com", SUBJECT, nonce,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300), EMAIL, true, null);
    }

    public static String token(Consumer<MutableClaims> customizer) {
        MutableClaims claims = new MutableClaims();
        customizer.accept(claims);
        return token(claims.key, claims.audience, claims.issuer, claims.subject, claims.nonce,
                claims.issuedAt, claims.expiresAt, claims.email, claims.emailVerified,
                claims.mutate);
    }

    private static String token(RSAKey key, String audience, String issuer, String subject,
                                String nonce, Instant issuedAt, Instant expiresAt,
                                String email, boolean emailVerified,
                                Consumer<JWTClaimsSet.Builder> mutate) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("email", email)
                    .claim("email_verified", emailVerified);
            if (nonce != null) {
                builder.claim("nonce", nonce);
            }
            if (mutate != null) {
                mutate.accept(builder);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    builder.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign a test ID token", exception);
        }
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not generate a test RSA key", exception);
        }
    }

    /** Mutable claim set so each test can vary exactly one aspect of the token. */
    public static final class MutableClaims {
        public RSAKey key = keyA();
        public String audience = AUDIENCE;
        public String issuer = "https://accounts.google.com";
        public String subject = SUBJECT;
        public String nonce;
        public Instant issuedAt = Instant.now().minusSeconds(5);
        public Instant expiresAt = Instant.now().plusSeconds(300);
        public String email = EMAIL;
        public boolean emailVerified = true;
        public Consumer<JWTClaimsSet.Builder> mutate;
    }
}
