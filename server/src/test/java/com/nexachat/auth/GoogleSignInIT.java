package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.auth.google.GoogleTestKeys;
import com.nexachat.auth.google.JwksServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google sign-in, link and unlink against a real PostgreSQL and a loopback
 * JWKS endpoint serving locally generated keys. No request ever reaches Google.
 *
 * <p>Tokens are minted by {@link GoogleTestKeys}; the nonce comes from the real
 * endpoint; the production Nimbus decoder verifies the signature, issuer,
 * audience and expiry.
 */
class GoogleSignInIT extends SecurityITBase {

    private static final String PHONE = "+84905556001";
    private static final JwksServer JWKS = JwksServer.start(List.of(GoogleTestKeys.keyA()));

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.google.signin.enabled", () -> "true");
        registry.add("nexachat.google.signin.web-client-id", () -> GoogleTestKeys.AUDIENCE);
        registry.add("nexachat.google.signin.jwks-uri", JWKS::uri);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private JwtService jwtService;

    @BeforeEach
    void cleanNonces() {
        jdbcTemplate.update("DELETE FROM google_signin_nonces");
        jdbcTemplate.update("DELETE FROM user_identities");
    }

    @Test
    void nonceIsIssuedHashedAndSingleUse() throws Exception {
        String nonce = issueNonce();

        String stored = jdbcTemplate.queryForObject(
                "SELECT nonce_hash FROM google_signin_nonces", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(nonce);
        assertThat(stored).isEqualTo(com.nexachat.auth.google.GoogleNonceService.sha256Hex(nonce));

        Registered user = register(PHONE);
        String token = GoogleTestKeys.token(nonce);
        link(user, token, nonce).andExpect(status().isNoContent());

        // The same nonce cannot be consumed by a second request.
        postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(1);
    }

    @Test
    void signInIssuesTokensThatRotate() throws Exception {
        Registered user = register(PHONE);
        String linkNonce = issueNonce();
        link(user, GoogleTestKeys.token(linkNonce), linkNonce).andExpect(status().isNoContent());

        String signInNonce = issueNonce();
        var result = postJson("/api/v1/auth/google",
                Map.of("idToken", GoogleTestKeys.token(signInNonce), "nonce", signInNonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.userId().toString()))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        // The access token is a normal access token: it authenticates REST and
        // passes the same JwtService verification a STOMP CONNECT uses.
        String accessToken = body.path("accessToken").asText();
        getWithAuth("/api/v1/users/me", accessToken).andExpect(status().isOk());
        assertThat(jwtService.parseAccessToken(accessToken).userId()).isEqualTo(user.userId());

        // ... and its refresh token participates in normal rotation.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", body.path("refreshToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString());
    }

    @Test
    void linkedAccountCanSignInAgainAfterItsRefreshTokenExpires() throws Exception {
        Registered user = register(PHONE);
        String linkNonce = issueNonce();
        link(user, GoogleTestKeys.token(linkNonce), linkNonce).andExpect(status().isNoContent());

        for (int attempt = 0; attempt < 2; attempt++) {
            String nonce = issueNonce();
            postJson("/api/v1/auth/google",
                    Map.of("idToken", GoogleTestKeys.token(nonce), "nonce", nonce))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(user.userId().toString()));
        }
    }

    @Test
    void unlinkedAccountIs404WithoutRevealingAccounts() throws Exception {
        String nonce = issueNonce();
        postJson("/api/v1/auth/google",
                Map.of("idToken", GoogleTestKeys.token(nonce), "nonce", nonce))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_NOT_LINKED"));
    }

    @Test
    void linkRequiresTheCurrentPassword() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueNonce();
        String token = GoogleTestKeys.token(nonce);

        postJson("/api/v1/auth/identities/google",
                Map.of("idToken", token, "nonce", nonce, "password", "wrongpass9"), user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities", Integer.class)).isZero();

        // The nonce was not consumed by a failed password check.
        link(user, token, nonce).andExpect(status().isNoContent());
    }

    @Test
    void linkingIsIdempotentForTheSameAccount() throws Exception {
        Registered user = register(PHONE);
        String first = issueNonce();
        link(user, GoogleTestKeys.token(first), first).andExpect(status().isNoContent());

        String second = issueNonce();
        link(user, GoogleTestKeys.token(second), second).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(1);
    }

    @Test
    void oneGoogleAccountCannotBeLinkedToTwoUsers() throws Exception {
        Registered first = register(PHONE);
        Registered second = register("+84905556002");
        String nonce = issueNonce();
        link(first, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        String otherNonce = issueNonce();
        link(second, GoogleTestKeys.token(otherNonce), otherNonce)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_ALREADY_LINKED"));
    }

    @Test
    void linkingASecondGoogleAccountToTheSameUserIsRefused() throws Exception {
        Registered user = register(PHONE);
        String first = issueNonce();
        link(user, GoogleTestKeys.token(first), first).andExpect(status().isNoContent());

        String second = issueNonce();
        link(user, GoogleTestKeys.token(claims -> {
            claims.nonce = second;
            claims.subject = "another-google-subject";
        }), second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_ALREADY_LINKED"));
    }

    @Test
    void unlinkNeedsThePasswordAndStopsGoogleSignIn() throws Exception {
        Registered user = register(PHONE);
        String linkNonce = issueNonce();
        link(user, GoogleTestKeys.token(linkNonce), linkNonce).andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/identities/google")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "wrongpass9"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/identities/google")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "secret123"))))
                .andExpect(status().isNoContent());

        String nonce = issueNonce();
        postJson("/api/v1/auth/google",
                Map.of("idToken", GoogleTestKeys.token(nonce), "nonce", nonce))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_NOT_LINKED"));
        getWithAuth("/api/v1/auth/identities", user.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identities").isEmpty());
    }

    @Test
    void linkedIdentitiesAreListedForTheOwnerOnly() throws Exception {
        Registered owner = register(PHONE);
        Registered other = register("+84905556003");
        String nonce = issueNonce();
        link(owner, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        getWithAuth("/api/v1/auth/identities", owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identities[0].provider").value("google"))
                .andExpect(jsonPath("$.identities[0].email").value(GoogleTestKeys.EMAIL))
                .andExpect(jsonPath("$.identities[0].linkedAt").isString())
                .andExpect(jsonPath("$.identities[0].subject").doesNotExist());

        getWithAuth("/api/v1/auth/identities", other.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identities").isEmpty());
    }

    @Test
    void identityRowsAreRemovedWithTheirUser() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueNonce();
        link(user, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.userId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isZero();
    }

    @Test
    void unlinkIsRefusedWhenTheAccountHasNoPassword() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueNonce();
        link(user, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", " ", user.userId());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/identities/google")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "secret123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_SIGN_IN_METHOD"));
    }

    @Test
    void rejectsTokensForAnotherAudienceIssuerExpiryOrKey() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueNonce();
        link(user, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        assertSignInRejected(claims ->
                claims.audience = "another-app.apps.googleusercontent.com");
        assertSignInRejected(claims -> claims.issuer = "https://evil.example.com");
        assertSignInRejected(claims -> {
            claims.issuedAt = java.time.Instant.now().minusSeconds(600);
            claims.expiresAt = java.time.Instant.now().minusSeconds(120);
        });
        assertSignInRejected(claims -> claims.key = GoogleTestKeys.keyC());
    }

    @Test
    void adoptsKeysGoogleRotatesInAfterACachedFetch() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueNonce();
        link(user, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        JWKS.publish(List.of(GoogleTestKeys.keyA(), GoogleTestKeys.keyB()));
        String rotatedNonce = issueNonce();
        postJson("/api/v1/auth/google", Map.of(
                        "idToken", GoogleTestKeys.token(claims -> {
                            claims.key = GoogleTestKeys.keyB();
                            claims.nonce = rotatedNonce;
                        }),
                        "nonce", rotatedNonce))
                .andExpect(status().isOk());
    }

    @Test
    void nonceEndpointIsRateLimitedPerIp() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            postJson("/api/v1/auth/google/nonce", Map.of(), null, remoteAddr("10.30.0.1"))
                    .andExpect(status().isOk());
        }
        postJson("/api/v1/auth/google/nonce", Map.of(), null, remoteAddr("10.30.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    private String issueNonce() throws Exception {
        var result = postJson("/api/v1/auth/google/nonce", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nonce").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("nonce").asText();
    }

    private org.springframework.test.web.servlet.ResultActions link(
            Registered user, String idToken, String nonce) throws Exception {
        return postJson("/api/v1/auth/identities/google",
                Map.of("idToken", idToken, "nonce", nonce, "password", "secret123"),
                user.accessToken());
    }

    private void assertSignInRejected(Consumer<GoogleTestKeys.MutableClaims> customizer)
            throws Exception {
        String nonce = issueNonce();
        String idToken = GoogleTestKeys.token(claims -> {
            claims.nonce = nonce;
            customizer.accept(claims);
        });
        postJson("/api/v1/auth/google", Map.of("idToken", idToken, "nonce", nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));
    }
}
