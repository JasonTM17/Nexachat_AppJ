package com.nexachat.auth;

import com.nexachat.auth.google.GoogleTestKeys;
import com.nexachat.auth.google.JwksServer;
import com.nexachat.recovery.OtpPurpose;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.DeletedAccountPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Passwordless accounts confirm deletion with the email code from
 * {@code POST /api/v1/users/me/deletion-code}. Phone fixtures belong only to
 * the password and unverified-email cases.
 */
@Import(AccountRecoveryFlowTest.MailTestConfiguration.class)
class GoogleOnlyAccountDeletionIT extends SecurityITBase {

    private static final JwksServer JWKS = JwksServer.start(List.of(GoogleTestKeys.keyA()));

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
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

    @Autowired
    private AccountRecoveryFlowTest.CapturingMailSender mailSender;

    @BeforeEach
    void clearMail() {
        mailSender.clear();
    }

    @Test
    void passwordlessDeletionRevokesSessionsRemovesIdentityAndTombstonesTheUser() throws Exception {
        String email = "gdel-success@example.com";
        Registered user = signUp("gdel-success", email);
        Registered secondSession = signInAgain("gdel-success", email);

        AccountRecoveryFlowTest.SentOtp code = requestCode(user);
        assertThat(code.recipient()).isEqualTo(email);
        assertThat(code.purpose()).isEqualTo(OtpPurpose.DELETE_ACCOUNT);
        assertThat(code.code()).matches("\\d{6}");

        deleteJson(user.accessToken(), "{\"code\":\"" + code.code() + "\"}")
                .andExpect(status().isNoContent());

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        getWithAuth("/api/v1/users/me", secondSession.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", user.refreshToken()))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", secondSession.refreshToken()))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) = 0 FROM refresh_tokens
                WHERE user_id = ? AND revoked_at IS NULL
                """, Boolean.class, user.userId())).isTrue();
        assertThat(identityCount(user)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_otps WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deleted_at IS NOT NULL
                   AND tokens_valid_after IS NOT NULL
                   AND phone IS NULL
                   AND email IS NULL
                   AND display_name = ?
                FROM users WHERE id = ?
                """, Boolean.class, DeletedAccountPolicy.DISPLAY_NAME, user.userId())).isTrue();
    }

    @Test
    void wrongDeletionCodeLeavesTheAccount() throws Exception {
        Registered user = signUp("gdel-wrong", "gdel-wrong@example.com");
        AccountRecoveryFlowTest.SentOtp code = requestCode(user);
        String wrong = code.code().equals("000000") ? "000001" : "000000";

        deleteJson(user.accessToken(), "{\"code\":\"" + wrong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        assertStillActive(user);
    }

    @Test
    void expiredDeletionCodeLeavesTheAccount() throws Exception {
        Registered user = signUp("gdel-expired", "gdel-expired@example.com");
        AccountRecoveryFlowTest.SentOtp code = requestCode(user);
        jdbcTemplate.update("""
                UPDATE email_otps SET expires_at = ?
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'
                """, Timestamp.from(Instant.now().minusSeconds(60)), user.userId());

        deleteJson(user.accessToken(), "{\"code\":\"" + code.code() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        assertStillActive(user);
    }

    @Test
    void reusedDeletionCodeLeavesTheAccount() throws Exception {
        Registered user = signUp("gdel-reused", "gdel-reused@example.com");
        AccountRecoveryFlowTest.SentOtp code = requestCode(user);
        jdbcTemplate.update("""
                UPDATE email_otps SET consumed_at = ?
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT' AND consumed_at IS NULL
                """, Timestamp.from(Instant.now()), user.userId());

        deleteJson(user.accessToken(), "{\"code\":\"" + code.code() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        assertStillActive(user);
    }

    @Test
    void deletionCodeIssuedToOneUserCannotDeleteAnother() throws Exception {
        Registered owner = signUp("gdel-owner", "gdel-owner@example.com");
        Registered other = signUp("gdel-other", "gdel-other@example.com");
        AccountRecoveryFlowTest.SentOtp code = requestCode(owner);

        deleteJson(other.accessToken(), "{\"code\":\"" + code.code() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempts FROM email_otps
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT' AND consumed_at IS NULL
                """, Integer.class, owner.userId())).isZero();
        assertStillActive(other);
        assertStillActive(owner);

        deleteJson(owner.accessToken(), "{\"code\":\"" + code.code() + "\"}")
                .andExpect(status().isNoContent());
        assertStillActive(other);
        assertThat(identityCount(owner)).isZero();
    }

    @Test
    void passwordUserIsNotDeletedByACodeAndPasswordDeletionIgnoresTheCode() throws Exception {
        String phone = ItPhoneRanges.phoneFor(GoogleOnlyAccountDeletionIT.class, "+84905559001");
        Registered user = register(phone);
        postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "secret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(true));

        deleteJson(user.accessToken(), "{\"code\":\"123456\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));
        assertStillActive(user);

        deleteJson(user.accessToken(), "{\"password\":\"secret123\",\"code\":\"999999\"}")
                .andExpect(status().isNoContent());
        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void passwordUserCannotRequestADeletionCode() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(GoogleOnlyAccountDeletionIT.class, "+84905559002"));

        requestDeletionCode(user.accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_REQUIRED"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'
                """, Integer.class, user.userId())).isZero();
        assertThat(mailSender.messages()).isEmpty();
        assertStillActive(user);
    }

    @Test
    void deletionCodeIsRateLimitedPerEmail() throws Exception {
        Registered user = signUp("gdel-rate", "gdel-rate@example.com");
        for (int sent = 0; sent < 5; sent++) {
            assertThat(requestCode(user).code()).matches("\\d{6}");
            ageLatestDeletionCode(user);
        }

        requestDeletionCode(user.accessToken())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(mailSender.messages()).isEmpty();
        assertStillActive(user);
    }

    @Test
    void unverifiedEmailCannotRequestADeletionCode() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(GoogleOnlyAccountDeletionIT.class, "+84905559003"));
        jdbcTemplate.update("UPDATE users SET password_hash = NULL WHERE id = ?", user.userId());

        requestDeletionCode(user.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        assertThat(mailSender.messages()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_otps WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertStillActive(user);
    }

    @Test
    void unauthenticatedDeletionRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/deletion-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private Registered signUp(String subject, String email) throws Exception {
        var result = postGoogle(subject, email)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.passwordSet").value(false))
                .andExpect(jsonPath("$.user.phone").doesNotExist())
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        Registered user = new Registered(
                java.util.UUID.fromString(body.path("user").path("id").asText()),
                body.path("accessToken").asText(),
                body.path("refreshToken").asText());
        assertThat(identityCount(user)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone IS NULL AND password_hash IS NULL AND email_verified_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
        return user;
    }

    private Registered signInAgain(String subject, String email) throws Exception {
        var result = postGoogle(subject, email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordSet").value(false))
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(
                java.util.UUID.fromString(body.path("user").path("id").asText()),
                body.path("accessToken").asText(),
                body.path("refreshToken").asText());
    }

    private ResultActions postGoogle(String subject, String email) throws Exception {
        String nonce = issueNonce();
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = nonce;
            claims.subject = subject;
            claims.email = email;
        });
        return postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce));
    }

    private String issueNonce() throws Exception {
        var result = postJson("/api/v1/auth/google/nonce", Map.of())
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("nonce").asText();
    }

    private AccountRecoveryFlowTest.SentOtp requestCode(Registered user) throws Exception {
        requestDeletionCode(user.accessToken())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        AccountRecoveryFlowTest.SentOtp sent = mailSender.take();
        assertThat(sent).isNotNull();
        return sent;
    }

    private ResultActions requestDeletionCode(String accessToken) throws Exception {
        var builder = post("/api/v1/users/me/deletion-code");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions deleteJson(String accessToken, String json) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private void ageLatestDeletionCode(Registered user) {
        jdbcTemplate.update("""
                UPDATE email_otps SET created_at = ?, expires_at = ?
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'
                """,
                Timestamp.from(Instant.now().minusSeconds(61)),
                Timestamp.from(Instant.now().plusSeconds(600)),
                user.userId());
    }

    private void assertStillActive(Registered user) throws Exception {
        getWithAuth("/api/v1/users/me", user.accessToken()).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
    }

    private int identityCount(Registered user) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_identities WHERE user_id = ?",
                Integer.class, user.userId());
    }
}
