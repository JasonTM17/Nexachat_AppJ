package com.nexachat.auth;

import com.nexachat.recovery.OtpPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@org.springframework.context.annotation.Import(AccountRecoveryFlowTest.MailTestConfiguration.class)
class AccountRecoverySecurityTest extends SecurityITBase {

    private static final String TEST_RESEND_KEY = "account-recovery-test-resend-key";

    @Autowired
    private AccountRecoveryFlowTest.CapturingMailSender mailSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void recoveryMailTestProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.mail.resend.api-key", () -> TEST_RESEND_KEY);
    }

    @BeforeEach
    void clearCapturedMail() {
        mailSender.clear();
    }

    @Test
    void emailIsNormalizedOwnerOnlyAndRequiresVerification() throws Exception {
        Registered owner = register("+84905552001");
        Registered other = register("+84905552002");

        putEmail(owner, "  OWNER@Example.com ").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp verification = mailSender.take();
        assertThat(verification).isNotNull();
        assertThat(verification.recipient()).isEqualTo("owner@example.com");
        assertThat(verification.purpose()).isEqualTo(OtpPurpose.VERIFY_EMAIL);
        assertThat(verification.code()).matches("\\d{6}");

        String storedHash = jdbcTemplate.queryForObject("SELECT code_hash FROM email_otps"
                        + " WHERE user_id = ? AND purpose = 'VERIFY_EMAIL'",
                String.class, owner.userId());
        assertThat(storedHash).hasSize(64).doesNotContain(verification.code());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        String otherView = mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", "+84905552001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(otherView).doesNotContain("owner@example.com").doesNotContain("emailVerified");

        mockMvc.perform(post("/api/v1/users/me/email/verify")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", verification.code()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.verified").value(true));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        mockMvc.perform(delete("/api/v1/users/me/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value((Object) null))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void publicRequestsDoNotEnumerateAndLogsOmitOtpAndProviderKey(CapturedOutput output) throws Exception {
        register("+84905552003"); // Existing account without a verified email.
        Registered withEmail = registerWithVerifiedEmail("+84905552004", "safe@example.com");

        String unknown = requestCode("+84909992003");
        String noEmail = requestCode("+84905552003");
        String withEmailResponse = requestCode("+84905552004");
        assertThat(unknown).isEqualTo("{}");
        assertThat(noEmail).isEqualTo(unknown);
        assertThat(withEmailResponse).isEqualTo(unknown);

        AccountRecoveryFlowTest.SentOtp deletion = mailSender.take();
        assertThat(deletion).isNotNull();
        assertThat(deletion.recipient()).isEqualTo("safe@example.com");
        assertThat(deletion.purpose()).isEqualTo(OtpPurpose.DELETE_ACCOUNT);
        assertThat(withEmail.userId()).isNotNull();
        assertThat(output.getOut()).doesNotContain(deletion.code()).doesNotContain(TEST_RESEND_KEY);
    }

    @Test
    void fiveWrongCodesKillTheOtpAndTheCorrectCodeStaysGeneric() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552005", "brute@example.com");
        requestCode("+84905552005");
        AccountRecoveryFlowTest.SentOtp issued = mailSender.take();
        String wrong = issued.code().equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            confirmDeletion("+84905552005", wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        }
        Integer attempts = jdbcTemplate.queryForObject("SELECT attempts FROM email_otps WHERE user_id = ?"
                + " AND purpose = 'DELETE_ACCOUNT'", Integer.class, user.userId());
        assertThat(attempts).isEqualTo(5);

        confirmDeletion("+84905552005", issued.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void expiryReuseAndDeletedAccountRequestsRemainGeneric() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552006", "expiry@example.com");
        requestCode("+84905552006");
        AccountRecoveryFlowTest.SentOtp expired = mailSender.take();
        jdbcTemplate.update("UPDATE email_otps SET expires_at = ? WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'",
                Timestamp.from(Instant.now().minusSeconds(1)), user.userId());
        confirmDeletion("+84905552006", expired.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        ageLatestOtp(user.userId(), OtpPurpose.DELETE_ACCOUNT);
        requestCode("+84905552006");
        AccountRecoveryFlowTest.SentOtp current = mailSender.take();
        confirmDeletion("+84905552006", current.code()).andExpect(status().isNoContent());
        confirmDeletion("+84905552006", current.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        assertThat(requestCode("+84905552006")).isEqualTo("{}");
        assertThat(mailSender.messages()).isEmpty();
    }

    @Test
    void otpDeletionUsesHardenedAccountDeletionAndClearsRecoveryData() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552012", "delete@example.com");
        requestCode("+84905552012");
        AccountRecoveryFlowTest.SentOtp deletion = mailSender.take();
        assertThat(deletion).isNotNull();

        Instant now = Instant.now();
        insertOtp(user.userId(), OtpPurpose.VERIFY_EMAIL, "pending@example.com", now);
        insertOtp(user.userId(), OtpPurpose.RESET_PASSWORD, "delete@example.com", now);

        jdbcTemplate.update("""
                INSERT INTO user_identities (id, user_id, provider, subject, email_at_link, linked_at)
                VALUES (?, ?, 'google', ?, 'linked@example.com', ?)
                """, java.util.UUID.randomUUID(), user.userId(), "deleted-subject-" + user.userId(),
                Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO google_signin_nonces
                    (id, nonce_hash, purpose, user_id, access_token_id_hash, created_at, expires_at)
                VALUES (?, ?, 'link', ?, ?, ?, ?)
                """, java.util.UUID.randomUUID(), hash64("a"), user.userId(), hash64("b"),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(60)));

        confirmDeletion("+84905552012", deletion.code()).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.userId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone IS NULL AND email IS NULL AND email_verified_at IS NULL"
                        + " FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tokens_valid_after IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_otps WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_identities WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM google_signin_nonces WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void newerOtpInvalidatesTheOldCodeAndVerifyCodeCannotDeleteAccount() throws Exception {
        Registered user = register("+84905552007");
        putEmail(user, "cross-purpose@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp verifyCode = mailSender.take();

        confirmDeletion("+84905552007", verifyCode.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        verifyEmail(user, verifyCode.code()).andExpect(status().isOk());

        requestCode("+84905552007");
        AccountRecoveryFlowTest.SentOtp old = mailSender.take();
        ageLatestOtp(user.userId(), OtpPurpose.DELETE_ACCOUNT);
        requestCode("+84905552007");
        AccountRecoveryFlowTest.SentOtp latest = mailSender.take();
        assertThat(latest.code()).isNotEqualTo(old.code());

        confirmDeletion("+84905552007", old.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        confirmDeletion("+84905552007", latest.code()).andExpect(status().isNoContent());
    }

    @Test
    void cooldownAndPerPhoneRateLimitsAreEnforced() throws Exception {
        Registered user = register("+84905552008");
        putEmail(user, "cooldown@example.com").andExpect(status().isAccepted());
        mailSender.take();
        putEmail(user, "changed@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("cooldown@example.com"));

        for (int i = 0; i < 10; i++) {
            assertThat(requestCode("+84909992008")).isEqualTo("{}");
        }
        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "+84909992008"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void concurrentVerificationCannotClaimOneEmailForTwoUsers(CapturedOutput output) throws Exception {
        Registered first = register("+84905552010");
        Registered second = register("+84905552011");
        putEmail(first, "race@example.com").andExpect(status().isAccepted());
        putEmail(second, "race@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp firstCode = mailSender.take();
        AccountRecoveryFlowTest.SentOtp secondCode = mailSender.take();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstResult = executor.submit(() -> verifyConcurrently(first, firstCode.code(), ready, start));
            Future<Integer> secondResult = executor.submit(() -> verifyConcurrently(second, secondCode.code(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int firstStatus = firstResult.get(10, TimeUnit.SECONDS);
            int secondStatus = secondResult.get(10, TimeUnit.SECONDS);

            assertThat(java.util.List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
            assertThat(output.getOut()).doesNotContain("race@example.com");
            Integer owners = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users"
                    + " WHERE email = 'race@example.com' AND email_verified_at IS NOT NULL AND deleted_at IS NULL",
                    Integer.class);
            assertThat(owners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void publicRequestIsLimitedPerIpAcrossPhoneValues() throws Exception {
        for (int i = 0; i < 30; i++) {
            requestCode("+849055531%03d".formatted(i));
        }
        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "+84905553999"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void passwordResetUsesVerifiedEmailAndRevokesAllSessions() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552009", "reset@example.com");
        Registered secondSession = login("+84905552009");
        String olderAccessToken = accessTokenIssuedBeforeRevocation(user.userId());

        requestPasswordReset("+84905552009");
        AccountRecoveryFlowTest.SentOtp reset = mailSender.take();
        assertThat(reset.purpose()).isEqualTo(OtpPurpose.RESET_PASSWORD);
        confirmPasswordReset("+84905552009", reset.code(), "new-secret123")
                .andExpect(status().isNoContent());

        postJson("/api/v1/auth/refresh", Map.of("refreshToken", user.refreshToken()))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", secondSession.refreshToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + olderAccessToken))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", "+84905552009", "password", "secret123"))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", "+84905552009", "password", "new-secret123"))
                .andExpect(status().isOk());
    }

    private Registered registerWithVerifiedEmail(String phone, String email)
            throws Exception {
        Registered user = register(phone);
        putEmail(user, email).andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp code = mailSender.take();
        verifyEmail(user, code.code()).andExpect(status().isOk());
        return user;
    }

    private org.springframework.test.web.servlet.ResultActions putEmail(Registered user, String email)
            throws Exception {
        return mockMvc.perform(put("/api/v1/users/me/email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private org.springframework.test.web.servlet.ResultActions verifyEmail(Registered user, String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/email/verify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))));
    }

    private int verifyConcurrently(Registered user, String code, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("verification race was not released");
        }
        return verifyEmail(user, code).andReturn().getResponse().getStatus();
    }

    private String requestCode(String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void requestPasswordReset(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{}", true));
    }

    private org.springframework.test.web.servlet.ResultActions confirmDeletion(String phone, String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/account-deletion/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))));
    }

    private org.springframework.test.web.servlet.ResultActions confirmPasswordReset(
            String phone, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "phone", phone, "code", code, "newPassword", newPassword))));
    }

    private void ageLatestOtp(java.util.UUID userId, OtpPurpose purpose) {
        jdbcTemplate.update("UPDATE email_otps SET created_at = ?, expires_at = ?"
                        + " WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL",
                Timestamp.from(Instant.now().minusSeconds(61)),
                Timestamp.from(Instant.now().plusSeconds(600)), userId, purpose.name());
    }

    private void insertOtp(java.util.UUID userId, OtpPurpose purpose, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO email_otps
                    (id, user_id, purpose, email, code_hash, expires_at, attempts, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?)
                """, java.util.UUID.randomUUID(), userId, purpose.name(), email, hash64("c"),
                Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
    }

    private static String hash64(String digit) {
        return digit.repeat(64);
    }
}
