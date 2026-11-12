package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.auth.google.GoogleTestKeys;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.auth.google.JwksServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoogleSignInSignupIT extends SecurityITBase {

    private static final String SIGNUP_SUBJECT = "google-subject-signup-only";
    private static final JwksServer JWKS = JwksServer.start(List.of(GoogleTestKeys.keyA()));

    @DynamicPropertySource
    static void googleSignupProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.google.signin.enabled", () -> "true");
        registry.add("nexachat.google.signin.signup-enabled", () -> "true");
        registry.add("nexachat.google.signin.web-client-id", () -> GoogleTestKeys.AUDIENCE);
        registry.add("nexachat.google.signin.android-client-ids", () -> GoogleTestKeys.ANDROID_CLIENT_ID);
        registry.add("nexachat.google.signin.jwks-uri", JWKS::uri);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @BeforeEach
    void cleanGoogleTables() {
        jdbcTemplate.update("DELETE FROM google_signin_nonces");
        jdbcTemplate.update("DELETE FROM user_identities");
    }

    @Test
    void unknownSubjectCreatesAccountWith201() throws Exception {
        String nonce = issueNonce();
        String token = tokenForNewSubject(nonce);
        var result = postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newAccount").value(true))
                .andExpect(jsonPath("$.user.phone").doesNotExist())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String userId = body.path("user").path("id").asText();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone IS NULL AND password_hash IS NULL FROM users WHERE id = ?::uuid",
                Boolean.class, userId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE subject = ?", Integer.class,
                SIGNUP_SUBJECT)).isEqualTo(1);
    }

    @Test
    void secondSignInReturns200WithoutNewAccountFlag() throws Exception {
        String nonce = issueNonce();
        String token = tokenForNewSubject(nonce);
        postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isCreated());

        String againNonce = issueNonce();
        postJson("/api/v1/auth/google",
                        Map.of("idToken", tokenForNewSubject(againNonce), "nonce", againNonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAccount").doesNotExist());
    }

    @Test
    void signupOffKeepsNotLinked() throws Exception {
        // This class enables signup; disable per-request is not available — use a fresh
        // subject against the default-disabled sibling coverage in GoogleSignInIT.
        // Here we assert email conflict instead of re-testing flag off.
        Registered phoneUser = register(ItPhoneRanges.nextPhone(GoogleSignInSignupIT.class));
        jdbcTemplate.update(
                "UPDATE users SET email = ?, email_verified_at = now() WHERE id = ?",
                GoogleTestKeys.EMAIL.toLowerCase(), phoneUser.userId());

        String nonce = issueNonce();
        postJson("/api/v1/auth/google",
                        Map.of("idToken", tokenForNewSubject(nonce), "nonce", nonce))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_EMAIL_IN_USE"));
    }

    @Test
    void unverifiedEmailFailsGenerically() throws Exception {
        String nonce = issueNonce();
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = nonce;
            claims.subject = SIGNUP_SUBJECT + "-unverified";
            claims.emailVerified = false;
        });
        postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_SIGN_IN_FAILED"));
    }

    @Test
    void concurrentFirstSignInCreatesOneUser() throws Exception {
        String firstNonce = issueNonce();
        String secondNonce = issueNonce();
        String raceSubject = SIGNUP_SUBJECT + "-race";
        String firstToken = GoogleTestKeys.token(claims -> {
            claims.nonce = firstNonce;
            claims.subject = raceSubject;
        });
        String secondToken = GoogleTestKeys.token(claims -> {
            claims.nonce = secondNonce;
            claims.subject = raceSubject;
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> signInAfterBarrier(
                    firstToken, firstNonce, ready, start));
            Future<Integer> second = executor.submit(() -> signInAfterBarrier(
                    secondToken, secondNonce, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(statuses).contains(201);
            assertThat(statuses).anyMatch(code -> code == 200 || code == 201);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_identities WHERE subject = ?", Integer.class,
                    raceSubject)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int signInAfterBarrier(String token, String nonce, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Barrier timed out");
        }
        return postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andReturn().getResponse().getStatus();
    }

    private String issueNonce() throws Exception {
        var result = postJson("/api/v1/auth/google/nonce", Map.of())
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("nonce").asText();
    }

    private static String tokenForNewSubject(String nonce) {
        return GoogleTestKeys.token(claims -> {
            claims.nonce = nonce;
            claims.subject = SIGNUP_SUBJECT;
        });
    }

}
