package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.auth.google.GoogleTestKeys;
import com.nexachat.auth.google.JwksServer;
import com.nexachat.testutil.TestAccessTokens;
import com.nexachat.testutil.TestWaits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
@Import(GoogleSignInIT.DeletionPauseConfiguration.class)
class GoogleSignInIT extends SecurityITBase {

    private static final String PHONE = ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556001");
    private static final JwksServer JWKS = JwksServer.start(List.of(GoogleTestKeys.keyA()));

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.google.signin.enabled", () -> "true");
        registry.add("nexachat.google.signin.web-client-id", () -> GoogleTestKeys.AUDIENCE);
        registry.add("nexachat.google.signin.android-client-ids", () -> GoogleTestKeys.ANDROID_CLIENT_ID);
        registry.add("nexachat.google.signin.jwks-uri", JWKS::uri);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private JwtService jwtService;

    @Autowired
    private TestAccountDeletionPause deletionPause;

    @BeforeEach
    void cleanNonces() {
        deletionPause.reset();
        jdbcTemplate.update("DELETE FROM google_signin_nonces");
        jdbcTemplate.update("DELETE FROM user_identities");
    }

    @Test
    void nonceIsIssuedHashedAndSingleUse() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueLinkNonce(user);

        String stored = jdbcTemplate.queryForObject(
                "SELECT nonce_hash FROM google_signin_nonces", String.class);
        String storedAccessTokenIdHash = jdbcTemplate.queryForObject(
                "SELECT access_token_id_hash FROM google_signin_nonces", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(nonce);
        assertThat(stored).isEqualTo(com.nexachat.auth.google.GoogleNonceService.sha256Hex(nonce));
        assertThat(storedAccessTokenIdHash).hasSize(64).isNotEqualTo(user.accessToken())
                .isEqualTo(com.nexachat.auth.google.GoogleNonceService.sha256Hex(
                        jwtService.parseAccessTokenClaims(user.accessToken()).tokenId()));

        String token = GoogleTestKeys.token(nonce);
        link(user, token, nonce).andExpect(status().isNoContent());

        // The same nonce cannot be consumed by a second request.
        link(user, token, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(1);
    }

    @Test
    void signInIssuesTokensThatRotate() throws Exception {
        Registered user = register(PHONE);
        String linkNonce = issueLinkNonce(user);
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
        String linkNonce = issueLinkNonce(user);
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
        String token = GoogleTestKeys.token(nonce);
        postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_NOT_LINKED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM google_signin_nonces WHERE nonce_hash = ?",
                Boolean.class, com.nexachat.auth.google.GoogleNonceService.sha256Hex(nonce)))
                .isTrue();
        postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));
    }

    @Test
    void publicSignInNonceCannotBeReusedToLinkUnderAnotherAccount() throws Exception {
        Registered first = register(PHONE);
        Registered second = register(ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556012"));
        String nonce = issueNonce();

        link(second, GoogleTestKeys.token(nonce), nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id IN (?, ?)", Integer.class,
                first.userId(), second.userId())).isZero();
    }

    @Test
    void linkNonceCannotBeUsedUnderAnotherAuthenticatedAccount() throws Exception {
        Registered nonceOwner = register(PHONE);
        Registered other = register(ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556013"));
        String nonce = issueLinkNonce(nonceOwner);

        link(other, GoogleTestKeys.token(nonce), nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id IN (?, ?)", Integer.class,
                nonceOwner.userId(), other.userId())).isZero();
    }

    @Test
    void linkNonceCannotBeUsedByAnotherSessionOfTheSameAccount() throws Exception {
        Registered requestingSession = register(PHONE);
        Registered otherSession = login(PHONE);
        assertThat(otherSession.accessToken()).isNotEqualTo(requestingSession.accessToken());
        String nonce = issueLinkNonce(requestingSession);
        String token = GoogleTestKeys.token(nonce);

        link(otherSession, token, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));

        // A failed cross-session attempt does not consume the nonce for its owner.
        link(requestingSession, token, nonce).andExpect(status().isNoContent());
    }

    @Test
    void linkNonceRequiresAnAccessTokenIdentifier() throws Exception {
        Registered user = register(PHONE);
        String legacyAccessToken = TestAccessTokens.accessTokenIssuedAt(
                jwtProperties, user.userId(), Instant.now());

        postJson("/api/v1/auth/identities/google/nonce", Map.of(), legacyAccessToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredLinkNonceCannotBeConsumed() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueLinkNonce(user);
        jdbcTemplate.update("UPDATE google_signin_nonces"
                        + " SET expires_at = created_at + INTERVAL '1 microsecond'"
                        + " WHERE nonce_hash = ?",
                com.nexachat.auth.google.GoogleNonceService.sha256Hex(nonce));

        link(user, GoogleTestKeys.token(nonce), nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM google_signin_nonces WHERE nonce_hash = ?",
                Boolean.class, com.nexachat.auth.google.GoogleNonceService.sha256Hex(nonce))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities", Integer.class)).isZero();
    }

    @Test
    void linkRequiresTheCurrentPassword() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueLinkNonce(user);
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
        String first = issueLinkNonce(user);
        link(user, GoogleTestKeys.token(first), first).andExpect(status().isNoContent());

        String second = issueLinkNonce(user);
        link(user, GoogleTestKeys.token(second), second).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(1);
    }

    @Test
    void oneGoogleAccountCannotBeLinkedToTwoUsers() throws Exception {
        Registered first = register(PHONE);
        Registered second = register(ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556002"));
        String nonce = issueLinkNonce(first);
        link(first, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        String otherNonce = issueLinkNonce(second);
        link(second, GoogleTestKeys.token(otherNonce), otherNonce)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_ALREADY_LINKED"));
    }

    @Test
    void concurrentLinksCannotAssignOneGoogleAccountToTwoUsers() throws Exception {
        Registered first = register(PHONE);
        Registered second = register(ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556002"));
        String firstNonce = issueLinkNonce(first);
        String secondNonce = issueLinkNonce(second);
        String firstToken = GoogleTestKeys.token(firstNonce);
        String secondToken = GoogleTestKeys.token(secondNonce);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstResponse = executor.submit(() -> linkAfterBarrier(
                    first, firstToken, firstNonce, ready, start));
            Future<Integer> secondResponse = executor.submit(() -> linkAfterBarrier(
                    second, secondToken, secondNonce, ready, start));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    firstResponse.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    secondResponse.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(204, 409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_identities WHERE provider = 'google' AND subject = ?",
                    Integer.class, GoogleTestKeys.SUBJECT)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int linkAfterBarrier(Registered user, String token, String nonce,
                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent link barrier timed out");
        }
        return link(user, token, nonce).andReturn().getResponse().getStatus();
    }

    @Test
    void linkingASecondGoogleAccountToTheSameUserIsRefused() throws Exception {
        Registered user = register(PHONE);
        String first = issueLinkNonce(user);
        link(user, GoogleTestKeys.token(first), first).andExpect(status().isNoContent());

        String second = issueLinkNonce(user);
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
        String linkNonce = issueLinkNonce(user);
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
        Registered other = register(ItPhoneRanges.phoneFor(GoogleSignInIT.class, "+84905556003"));
        String nonce = issueLinkNonce(owner);
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
        String nonce = issueLinkNonce(user);
        link(user, GoogleTestKeys.token(nonce), nonce).andExpect(status().isNoContent());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.userId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isZero();
    }

    @Test
    void accountDeletionRemovesGoogleDataAndDeletedIdentityCannotSignIn() throws Exception {
        Registered user = register(PHONE);
        String linkNonce = issueLinkNonce(user);
        link(user, GoogleTestKeys.token(linkNonce), linkNonce).andExpect(status().isNoContent());
        String unusedLinkNonce = issueLinkNonce(user);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM google_signin_nonces
                WHERE user_id = ? AND nonce_hash = ?
                """, Integer.class, user.userId(),
                com.nexachat.auth.google.GoogleNonceService.sha256Hex(unusedLinkNonce))).isEqualTo(1);
        int refreshRowsBeforeDeletion = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user.userId());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "secret123"))))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM google_signin_nonces WHERE user_id = ?", Integer.class,
                user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(refreshRowsBeforeDeletion);

        int refreshRowsAfterDeletion = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user.userId());
        assertGoogleSignInFailsWithoutIssuingSession();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(refreshRowsAfterDeletion);

        // Simulate a stale identity row to exercise the deleted-user guard itself.
        jdbcTemplate.update("""
                INSERT INTO user_identities (user_id, provider, subject, email_at_link)
                VALUES (?, 'google', ?, ?)
                """, user.userId(), GoogleTestKeys.SUBJECT, GoogleTestKeys.EMAIL);
        assertGoogleSignInFailsWithoutIssuingSession();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class,
                user.userId())).isEqualTo(refreshRowsAfterDeletion);
    }

    @Test
    void linkNonceRequestWaitingBehindDeletionCannotLeaveOwnedNonce() throws Exception {
        Registered user = register(PHONE);
        CountDownLatch deletionAtTombstone = new CountDownLatch(1);
        CountDownLatch resumeDeletion = new CountDownLatch(1);
        deletionPause.beforeTombstone = () -> {
            deletionAtTombstone.countDown();
            try {
                if (!resumeDeletion.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to resume account deletion");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        };

        HikariDataSource hikari = (HikariDataSource) jdbcTemplate.getDataSource();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection observer = DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
            Future<Integer> deletion = executor.submit(() -> mockMvc.perform(delete("/api/v1/users/me")
                            .header("Authorization", "Bearer " + user.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("password", "secret123"))))
                    .andReturn().getResponse().getStatus());
            assertThat(deletionAtTombstone.await(30, TimeUnit.SECONDS)).isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> linkNonce = executor.submit(() -> {
                try {
                    return postJson("/api/v1/auth/identities/google/nonce", Map.of(), user.accessToken())
                            .andReturn();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            awaitLinkNonceLockWait(observer);

            resumeDeletion.countDown();
            assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo(204);
            var result = linkNonce.get(60, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("USER_NOT_FOUND");
        } finally {
            resumeDeletion.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM google_signin_nonces WHERE user_id = ?", Integer.class,
                user.userId())).isZero();
    }

    private void assertGoogleSignInFailsWithoutIssuingSession() throws Exception {
        String signInNonce = issueNonce();
        postJson("/api/v1/auth/google", Map.of(
                        "idToken", GoogleTestKeys.token(signInNonce), "nonce", signInNonce))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_NOT_LINKED"));
    }

    private void awaitLinkNonceLockWait(Connection observer) {
        try (PreparedStatement query = observer.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND (query ILIKE '%users%' OR query ILIKE '%google_signin_nonces%')
                )
                """)) {
            TestWaits.until("Google link nonce request to wait behind the account deletion row lock", () -> {
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getBoolean(1);
                } catch (SQLException exception) {
                    throw new IllegalStateException("Could not observe the planned PostgreSQL lock wait", exception);
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not observe the planned PostgreSQL lock wait", exception);
        }
    }

    @TestConfiguration
    static class DeletionPauseConfiguration {
        @Bean
        @Primary
        TestAccountDeletionPause testAccountDeletionPause() {
            return new TestAccountDeletionPause();
        }
    }

    static final class TestAccountDeletionPause implements com.nexachat.user.AccountDeletionCoordination {
        volatile Runnable beforeTombstone = () -> {};

        void reset() {
            beforeTombstone = () -> {};
        }

        @Override
        public void beforeTombstone(java.util.UUID userId) {
            beforeTombstone.run();
        }
    }

    @Test
    void unlinkIsRefusedWhenTheAccountHasNoPassword() throws Exception {
        Registered user = register(PHONE);
        String nonce = issueLinkNonce(user);
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
        String nonce = issueLinkNonce(user);
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
        String nonce = issueLinkNonce(user);
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
    void jwksFetchFailureRejectsAnUnknownSigningKeyGenerically() throws Exception {
        JWKS.failWith(503);
        try {
            assertSignInRejected(claims -> claims.key = GoogleTestKeys.keyC());
        } finally {
            JWKS.publish(List.of(GoogleTestKeys.keyA()));
        }
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

    private String issueLinkNonce(Registered user) throws Exception {
        var result = postJson("/api/v1/auth/identities/google/nonce", Map.of(), user.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nonce").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("nonce").asText();
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
